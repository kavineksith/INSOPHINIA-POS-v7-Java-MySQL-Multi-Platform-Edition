package com.insophinia.pos.service;

import com.insophinia.pos.dto.UserDtos.CreateUserRequest;
import com.insophinia.pos.dto.UserDtos.UpdateUserRequest;
import com.insophinia.pos.dto.UserDtos.UserResponse;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.entity.UserRole;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ConflictException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final com.insophinia.pos.security.SessionService sessionService;
    private final SyncHub syncHub;
    private final SecurityEventService securityEvents;
    private final long passwordExpiryDays;

    public UserService(UserRepository repository, PasswordEncoder passwordEncoder,
                       com.insophinia.pos.security.SessionService sessionService, SyncHub syncHub,
                       SecurityEventService securityEvents,
                       @org.springframework.beans.factory.annotation.Value("${app.security.password-expiry-days:90}") long passwordExpiryDays) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.syncHub = syncHub;
        this.securityEvents = securityEvents;
        this.passwordExpiryDays = passwordExpiryDays;
    }

    @Transactional(readOnly = true)
    public Page<User> list(String search, Boolean includeDeleted, Boolean deletedOnly, Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<User> spec;
        if (Boolean.TRUE.equals(deletedOnly)) {
            spec = (root, q, cb) -> cb.isNotNull(root.get("deletedAt"));
        } else if (Boolean.TRUE.equals(includeDeleted)) {
            spec = (root, q, cb) -> cb.conjunction();
        } else {
            spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("username")), like),
                    cb.like(cb.lower(root.get("email")), like), cb.like(cb.lower(root.get("firstName")), like),
                    cb.like(cb.lower(root.get("lastName")), like)));
        }
        return repository.findAll(spec, pageable);
    }
    public Page<User> list(Pageable pageable) { return list(null, false, false, pageable); }

    public User get(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        if (repository.existsByUsername(req.username())) {
            throw new ConflictException("Username is already taken");
        }
        if (repository.existsByEmail(req.email())) {
            throw new ConflictException("Email is already registered");
        }
        User u = new User();
        u.setUsername(req.username());
        u.setEmail(req.email());
        u.setFirstName(req.firstName());
        u.setLastName(req.lastName());
        u.setRole(parseRole(req.role()));
        validatePassword(req.temporaryPassword(), req.username());
        u.setPassword(passwordEncoder.encode(req.temporaryPassword()));
        u.setMustChangePassword(true); // forced change on first login — same as reference app
        u.setPasswordChangedAt(Instant.now());
        u.setPasswordExpiresAt(Instant.now().plus(passwordExpiryDays, ChronoUnit.DAYS));
        repository.save(u);
        syncHub.broadcast("USER_CREATED", "user", Map.of("userId", u.getId().toString()));
        return toResponse(u);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest req) {
        User u = get(id);
        if (req.email() != null) u.setEmail(req.email());
        if (req.firstName() != null) u.setFirstName(req.firstName());
        if (req.lastName() != null) u.setLastName(req.lastName());
        if (req.role() != null) u.setRole(parseRole(req.role()));
        if (req.active() != null) u.setActive(req.active());
        repository.save(u);
        syncHub.broadcast("USER_UPDATED", "user", Map.of("userId", id.toString()));
        return toResponse(u);
    }

    @Transactional
    public void delete(UUID id, UUID requestingUserId, boolean hard) {
        if (id.equals(requestingUserId)) {
            throw new BadRequestException("You cannot delete your own account");
        }
        if (hard) {
            repository.findById(id).ifPresent(u -> {
                sessionService.revokeAll(id, "account_hard_deleted");
                repository.delete(u);
            });
            return;
        }
        User u = get(id);
        u.setDeletedAt(Instant.now());
        u.setActive(false);
        repository.save(u);
        sessionService.revokeAll(id, "account_deleted");
        syncHub.broadcast("USER_DELETED", "user", Map.of("userId", id.toString()));
    }

    @Transactional
    public User restore(UUID id) {
        User u = repository.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        u.setDeletedAt(null);
        u.setActive(true);
        return repository.save(u);
    }

    @Transactional
    public void resetPassword(UUID id, String newPassword) {
        User u = get(id);
        validatePassword(newPassword, u.getUsername());
        u.setPassword(passwordEncoder.encode(newPassword));
        u.setMustChangePassword(true);
        u.setPasswordChangedAt(Instant.now());
        u.setPasswordExpiresAt(Instant.now().plus(passwordExpiryDays, ChronoUnit.DAYS));
        u.setFailedLoginAttempts(0);
        u.setLockedUntil(null); // reset also unlocks — matches the reference reset flow
        repository.save(u);
        securityEvents.log(id, "PASSWORD_RESET", "unknown", "", "Administrator reset the password", "high");
        sessionService.revokeAll(id, "password_reset");
        syncHub.broadcast("USER_PASSWORD_RESET", "user", Map.of("userId", id.toString()));
    }


    public static void validatePassword(String password, String username) {
        if (password == null || password.length() < 12 || password.length() > 30
                || !password.matches(".*[A-Z].*") || !password.matches(".*[a-z].*")
                || !password.matches(".*[0-9].*") || !password.matches(".*[^A-Za-z0-9].*")) {
            throw new BadRequestException("Password must be 12–30 characters and include uppercase, lowercase, number and special character");
        }
        if (username != null && !username.isBlank() && password.toLowerCase(java.util.Locale.ROOT).contains(username.toLowerCase(java.util.Locale.ROOT))) {
            throw new BadRequestException("Password must not contain the username");
        }
    }

    private static UserRole parseRole(String role) {
        try {
            return UserRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Role must be one of: master_admin, admin, supervisor, cashier");
        }
    }

    public static UserResponse toResponse(User u) {
        return new UserResponse(u.getId(), u.getUsername(), u.getEmail(), u.getRole().name(),
                u.getFirstName(), u.getLastName(), u.isActive(), u.isTwoFactorEnabled(),
                u.isLocked(), u.isMustChangePassword(), u.getCreatedAt());
    }
}
