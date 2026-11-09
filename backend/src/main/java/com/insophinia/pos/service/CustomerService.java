package com.insophinia.pos.service;

import com.insophinia.pos.dto.CatalogDtos.CustomerRequest;
import com.insophinia.pos.dto.CatalogDtos.CustomerResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationResult;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationStatus;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ConflictException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.CustomerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class CustomerService {

    /** Verification token lifetime: 24 hours from issuance. */
    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

    private final CustomerRepository repository;
    private final SyncHub syncHub;
    private final EmailService emailService;
    private final SettingsService settings;
    private final String publicAppUrl;

    public CustomerService(CustomerRepository repository, SyncHub syncHub,
                           EmailService emailService, SettingsService settings,
                           @Value("${app.public-url:http://127.0.0.1:5173}") String publicAppUrl) {
        this.repository = repository;
        this.syncHub = syncHub;
        this.emailService = emailService;
        this.settings = settings;
        this.publicAppUrl = publicAppUrl;
    }

    @Transactional(readOnly = true)
    public Page<Customer> list(String search, Instant from, Instant to, Boolean includeDeleted, Boolean deletedOnly, Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<Customer> spec;
        if (Boolean.TRUE.equals(deletedOnly)) {
            spec = (root, q, cb) -> cb.isNotNull(root.get("deletedAt"));
        } else if (Boolean.TRUE.equals(includeDeleted)) {
            spec = (root, q, cb) -> cb.conjunction();
        } else {
            spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        }
        if (from != null) spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null) spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("email")), like), cb.like(cb.lower(root.get("phone")), like)));
        }
        return repository.findAll(spec, pageable);
    }

    public Customer get(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
    }

    @Transactional
    public CustomerResponse create(CustomerRequest req) {
        // Normalize email + phone first so that case-insensitive uniqueness
        // checks (and subsequent lookups by the canonical form) work
        // consistently — without this, "Alice@Example.com" would slip past
        // a row that already exists as "alice@example.com".
        String normalizedEmail = req.email() == null || req.email().isBlank()
                ? null : req.email().toLowerCase(Locale.ROOT);
        if (normalizedEmail != null
                && repository.findByEmailAndDeletedAtIsNull(normalizedEmail).isPresent()) {
            throw new ConflictException("A customer with this email already exists");
        }
        if (req.phone() != null && !req.phone().isBlank()
                && repository.findByPhoneAndDeletedAtIsNull(req.phone()).isPresent()) {
            throw new ConflictException("A customer with this phone number already exists");
        }
        Customer c = new Customer();
        apply(c, req);
        // Reset verification lifecycle when email changes / on create.
        if (c.getEmail() != null && !c.getEmail().isBlank()) {
            issueVerificationToken(c);
        }
        repository.save(c);
        syncHub.broadcast("CUSTOMER_CREATED", "customer", Map.of("customerId", c.getId().toString()));
        sendVerificationEmailIfEnabled(c);
        return toResponse(c);
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerRequest req) {
        Customer c = get(id);
        // Detect email change (case-insensitively, since we lowercase before
        // storage) to invalidate any prior verification.
        String newEmail = req.email() == null || req.email().isBlank()
                ? null : req.email().toLowerCase(Locale.ROOT);
        boolean emailChanged = !java.util.Objects.equals(c.getEmail(), newEmail);
        apply(c, req);
        if (emailChanged) {
            c.setEmailVerified(false);
            if (c.getEmail() != null && !c.getEmail().isBlank()) {
                issueVerificationToken(c);
                sendVerificationEmailIfEnabled(c);
            } else {
                c.setEmailVerificationToken(null);
                c.setEmailVerificationExpiresAt(null);
            }
        }
        repository.save(c);
        syncHub.broadcast("CUSTOMER_UPDATED", "customer", Map.of("customerId", id.toString()));
        return toResponse(c);
    }

    @Transactional
    public void delete(UUID id, boolean hard) {
        if (hard) {
            repository.findById(id).ifPresent(c -> {
                repository.delete(c);
                syncHub.broadcast("CUSTOMER_HARD_DELETED", "customer", Map.of("customerId", id.toString()));
            });
            return;
        }
        Customer c = get(id);
        c.setDeletedAt(Instant.now());
        repository.save(c);
        syncHub.broadcast("CUSTOMER_DELETED", "customer", Map.of("customerId", id.toString()));
    }

    @Transactional
    public Customer restore(UUID id) {
        Customer c = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer not found"));
        c.setDeletedAt(null);
        return repository.save(c);
    }

    // =========================================================================
    // Email verification
    // =========================================================================

    /**
     * Public entrypoint called by GET /api/customers/verify-email?token=...
     * Marks the customer as verified if the token is unexpired and matches.
     */
    @Transactional
    public CustomerVerificationResult verifyEmailByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Missing verification token");
        }
        Customer c = repository.findByEmailVerificationTokenAndDeletedAtIsNull(token)
                .orElseThrow(() -> new ResourceNotFoundException("Verification token not found or already consumed"));
        if (c.getEmailVerificationExpiresAt() == null
                || c.getEmailVerificationExpiresAt().isBefore(Instant.now())) {
            // Burn the expired token so it can't be retried indefinitely.
            c.setEmailVerificationToken(null);
            c.setEmailVerificationExpiresAt(null);
            repository.save(c);
            throw new BadRequestException("Verification token has expired. Request a new one.");
        }
        c.setEmailVerified(true);
        c.setEmailVerificationToken(null);
        c.setEmailVerificationExpiresAt(null);
        repository.save(c);
        syncHub.broadcast("CUSTOMER_UPDATED", "customer", Map.of("customerId", c.getId().toString(), "verified", true));
        return new CustomerVerificationResult(c.getId(), c.getEmail(), true);
    }

    /**
     * Admin/manager endpoint: regenerate and resend the verification email.
     * Returns the new token's expiry; the token itself is NOT exposed.
     */
    @Transactional
    public CustomerVerificationStatus resendVerification(UUID customerId) {
        Customer c = get(customerId);
        if (c.getEmail() == null || c.getEmail().isBlank()) {
            throw new BadRequestException("Customer has no email address on file");
        }
        if (c.isEmailVerified()) {
            // Idempotent: don't issue a new token if already verified.
            return new CustomerVerificationStatus(c.getId(), c.getEmail(), true, null);
        }
        issueVerificationToken(c);
        repository.save(c);
        sendVerificationEmailIfEnabled(c);
        return new CustomerVerificationStatus(c.getId(), c.getEmail(), false, c.getEmailVerificationExpiresAt());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void apply(Customer c, CustomerRequest req) {
        c.setName(req.name());
        // Normalize email to lowercase to keep uniqueness checks case-insensitive
        // without needing a case-insensitive collation or expression index.
        c.setEmail(req.email() == null || req.email().isBlank() ? null : req.email().toLowerCase(Locale.ROOT));
        c.setPhone(req.phone() == null || req.phone().isBlank() ? null : req.phone());
    }

    private void issueVerificationToken(Customer c) {
        byte[] bytes = new byte[32]; // 256-bit token, hex-encoded to 64 chars
        new SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        c.setEmailVerificationToken(sb.toString());
        c.setEmailVerificationExpiresAt(Instant.now().plus(VERIFICATION_TTL));
    }

    private void sendVerificationEmailIfEnabled(Customer c) {
        if (!settings.getBool(SettingsService.KEY_EMAIL_ENABLED, false)) return;
        if (c.getEmail() == null || c.getEmail().isBlank()) return;
        if (c.getEmailVerificationToken() == null) return;
        String link = publicAppUrl.replaceAll("/+$", "")
                + "/verify-email?token=" + c.getEmailVerificationToken();
        String subject = "Verify your email — " + settings.getOrDefault(SettingsService.KEY_SHOP_NAME, "INSOPHINIA POS");
        String body = """
            Hello %s,

            Please confirm your email address by visiting the link below.
            The link expires in 24 hours.

            %s

            If you did not create an account, you can safely ignore this email.

            — %s
            """.formatted(c.getName(), link, settings.getOrDefault(SettingsService.KEY_SHOP_NAME, "INSOPHINIA POS"));
        try {
            emailService.send(c.getEmail(), subject, body);
        } catch (Exception ex) {
            // Email send failure must not roll back the customer create/update.
            // The token is still on file and can be re-sent via resend-verification.
            org.slf4j.LoggerFactory.getLogger(getClass()).warn(
                    "Failed to send verification email to {}: {}", c.getEmail(), ex.getMessage());
        }
    }

    public static CustomerResponse toResponse(Customer c) {
        return new CustomerResponse(c.getId(), c.getName(), c.getEmail(), c.getPhone(),
                c.getLoyaltyPoints(), c.isEmailVerified(), c.getCreatedAt());
    }
}
