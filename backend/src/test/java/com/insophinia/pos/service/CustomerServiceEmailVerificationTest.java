package com.insophinia.pos.service;

import com.insophinia.pos.dto.CatalogDtos.CustomerRequest;
import com.insophinia.pos.dto.CatalogDtos.CustomerResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationResult;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationStatus;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Pure-unit tests for the verification-lifecycle branches of
 * {@link CustomerService}. These tests do not require a database — they
 * exercise the service's branching logic with a mocked repository so they
 * run quickly and deterministically under any CI environment.
 *
 * Scope:
 *   - Token verification success path
 *   - Token verification failure paths (missing token, expired, not found)
 *   - Resend: fresh token issued, idempotent on verified customers
 *   - Create: token issued when email is provided; no token when email is blank
 *
 * The service is constructed manually in {@link #setUp()} rather than relying
 * on {@code @InjectMocks} so that the {@code @Value}-annotated
 * {@code publicAppUrl} constructor parameter is provided with a non-null
 * default (which Mockito cannot infer).
 */
@ExtendWith(MockitoExtension.class)
class CustomerServiceEmailVerificationTest {

    @Mock private CustomerRepository repository;
    @Mock private SyncHub syncHub;
    @Mock private EmailService emailService;
    @Mock private SettingsService settings;

    private CustomerService service;

    @BeforeEach
    void setUp() {
        // publicAppUrl is normally injected from app.public-url. In unit tests
        // we pass a literal default so the verification email builder doesn't
        // throw a NullPointer when String.replaceAll is called.
        service = new CustomerService(repository, syncHub, emailService, settings,
                "http://test.local:5173");
    }

    private Customer makeCustomer(String token, Instant expiresAt, boolean verified) {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setName("Test Customer");
        c.setEmail("test@example.com");
        c.setEmailVerified(verified);
        c.setEmailVerificationToken(token);
        c.setEmailVerificationExpiresAt(expiresAt);
        return c;
    }

    @Test
    void verifyByToken_marksVerified_andBurnsToken_andBroadcasts() {
        String token = "deadbeef".repeat(8);
        Customer c = makeCustomer(token, Instant.now().plusSeconds(3600), false);
        when(repository.findByEmailVerificationTokenAndDeletedAtIsNull(token)).thenReturn(Optional.of(c));
        when(repository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        CustomerVerificationResult result = service.verifyEmailByToken(token);

        assertTrue(result.verified());
        assertEquals(c.getId(), result.customerId());
        assertEquals(c.getEmail(), result.email());
        assertTrue(c.isEmailVerified());
        assertNull(c.getEmailVerificationToken());
        assertNull(c.getEmailVerificationExpiresAt());
        verify(syncHub).broadcast(eq("CUSTOMER_UPDATED"), eq("customer"), any());
    }

    @Test
    void verifyByToken_blankTokenThrowsBadRequest() {
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.verifyEmailByToken(""));
        assertTrue(ex.getMessage().toLowerCase().contains("missing"));
        verifyNoInteractions(repository);
    }

    @Test
    void verifyByToken_unknownTokenThrowsNotFound() {
        when(repository.findByEmailVerificationTokenAndDeletedAtIsNull("nosuchtoken"))
                .thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.verifyEmailByToken("nosuchtoken"));
    }

    @Test
    void verifyByToken_expiredTokenBurnsTokenAndThrowsBadRequest() {
        String token = "expired-token-of-32-bytes-aaaaaaaaaa";
        Customer c = makeCustomer(token, Instant.now().minusSeconds(60), false);
        when(repository.findByEmailVerificationTokenAndDeletedAtIsNull(token)).thenReturn(Optional.of(c));
        when(repository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.verifyEmailByToken(token));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"));
        assertNull(c.getEmailVerificationToken(), "Expired token must be burned");
        assertNull(c.getEmailVerificationExpiresAt());
        assertFalse(c.isEmailVerified());
    }

    @Test
    void resendVerification_customerWithoutEmail_throwsBadRequest() {
        Customer c = new Customer();
        c.setId(UUID.randomUUID());
        c.setName("Phone Only");
        c.setEmail(null);
        when(repository.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> service.resendVerification(c.getId()));
        assertTrue(ex.getMessage().toLowerCase().contains("no email"));
    }

    @Test
    void resendVerification_alreadyVerified_isIdempotentNoNewToken() {
        Customer c = makeCustomer("any", null, true);
        when(repository.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));

        CustomerVerificationStatus status = service.resendVerification(c.getId());
        assertTrue(status.emailVerified());
        assertNull(status.emailVerificationExpiresAt());
        verify(repository, never()).save(any(Customer.class));
        verifyNoInteractions(emailService);
    }

    @Test
    void resendVerification_unverified_issuesFreshToken_andSendsEmail() {
        Customer c = makeCustomer("old-token", Instant.now().minusSeconds(60), false);
        when(repository.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));
        when(repository.save(any(Customer.class))).thenAnswer(inv -> inv.getArgument(0));
        when(settings.getBool(eq(SettingsService.KEY_EMAIL_ENABLED), eq(false))).thenReturn(true);

        CustomerVerificationStatus status = service.resendVerification(c.getId());

        assertFalse(status.emailVerified());
        assertNotNull(status.emailVerificationExpiresAt());
        assertNotNull(c.getEmailVerificationToken());
        assertNotEquals("old-token", c.getEmailVerificationToken(),
                "A fresh token must replace the old one");
        verify(emailService).send(eq(c.getEmail()), anyString(), anyString());
    }

    @Test
    void create_customerWithEmail_issuesToken_andMarksUnverified() {
        when(repository.findByEmailAndDeletedAtIsNull("alice@example.com")).thenReturn(Optional.empty());
        when(repository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        CustomerResponse response = service.create(new CustomerRequest("Alice", "Alice@Example.com", null));
        verify(repository).save(captor.capture());
        Customer saved = captor.getValue();

        assertEquals("alice@example.com", saved.getEmail(),
                "Email must be lowercased for case-insensitive uniqueness");
        assertFalse(saved.isEmailVerified());
        assertNotNull(saved.getEmailVerificationToken());
        assertNotNull(saved.getEmailVerificationExpiresAt());
        assertTrue(saved.getEmailVerificationExpiresAt().isAfter(Instant.now()));
        assertFalse(response.emailVerified());
    }

    @Test
    void create_customerWithBlankEmail_doesNotIssueToken() {
        when(repository.save(any(Customer.class))).thenAnswer(inv -> {
            Customer c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });

        CustomerResponse response = service.create(new CustomerRequest("Bob Phone Only", null, "+94770000000"));
        assertFalse(response.emailVerified());
        // For phone-only customers, no token is expected on the persisted row.
        verify(repository, never()).findByEmailAndDeletedAtIsNull(anyString());
    }
}
