package com.insophinia.pos;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerRequest;
import com.insophinia.pos.dto.CatalogDtos.CustomerResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationResult;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationStatus;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.service.CustomerService;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration coverage for the customer email verification lifecycle that
 * was added as part of the the current version → Spring Boot port completion.
 *
 * Exercises:
 *   - On customer create, a verification token + 24-hour expiry is issued
 *     and `emailVerified` is false.
 *   - Resending verification issues a fresh token and resets the expiry.
 *   - Verifying by token marks the customer verified and burns the token.
 *   - Reusing a consumed token throws ResourceNotFoundException.
 *   - Verifying with an expired token throws BadRequestException.
 *
 * Does not exercise SMTP delivery — `email_enabled` defaults to false in the
 * test profile, so no outbound email is attempted.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerEmailVerificationIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private CustomerRepository customerRepository;

    private CustomerResponse createCustomerWithEmail(String name, String email) {
        return customerService.create(new CustomerRequest(name, email, null));
    }

    @Test
    void createCustomerWithEmailIssuesVerificationToken() {
        CustomerResponse c = createCustomerWithEmail("Alice Tester", "alice+" + UUID.randomUUID() + "@example.com");
        assertNotNull(c.id());
        assertFalse(c.emailVerified(), "Newly-created customer must be unverified");

        Customer stored = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        assertNotNull(stored.getEmailVerificationToken(), "Token must be issued on create");
        assertNotNull(stored.getEmailVerificationExpiresAt());
        assertTrue(stored.getEmailVerificationExpiresAt().isAfter(Instant.now()),
                "Token expiry must be in the future");
    }

    @Test
    void resendVerificationIssuesFreshToken() {
        CustomerResponse c = createCustomerWithEmail("Bob Tester", "bob+" + UUID.randomUUID() + "@example.com");
        Customer before = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        String firstToken = before.getEmailVerificationToken();

        CustomerVerificationStatus status = customerService.resendVerification(c.id());
        assertEquals(c.id(), status.customerId());
        assertEquals(c.email(), status.email());
        assertFalse(status.emailVerified());

        Customer after = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        assertNotEquals(firstToken, after.getEmailVerificationToken(),
                "Resending must generate a new token so a leaked link can't be reused");
        assertNotNull(after.getEmailVerificationExpiresAt());
        assertTrue(after.getEmailVerificationExpiresAt().isAfter(Instant.now()));
    }

    @Test
    void verifyByTokenMarksVerifiedAndBurnsToken() {
        CustomerResponse c = createCustomerWithEmail("Carol Tester", "carol+" + UUID.randomUUID() + "@example.com");
        Customer stored = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        String token = stored.getEmailVerificationToken();

        CustomerVerificationResult result = customerService.verifyEmailByToken(token);
        assertEquals(c.id(), result.customerId());
        assertEquals(c.email(), result.email());
        assertTrue(result.verified());

        Customer after = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        assertTrue(after.isEmailVerified());
        assertNull(after.getEmailVerificationToken(), "Token must be burned after use");
        assertNull(after.getEmailVerificationExpiresAt());
    }

    @Test
    void reusedTokenThrowsNotFound() {
        CustomerResponse c = createCustomerWithEmail("Dave Tester", "dave+" + UUID.randomUUID() + "@example.com");
        Customer stored = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        String token = stored.getEmailVerificationToken();
        customerService.verifyEmailByToken(token);

        // Second use must fail — the token has been burned.
        assertThrows(ResourceNotFoundException.class, () -> customerService.verifyEmailByToken(token));
    }

    @Test
    void expiredTokenThrowsBadRequest() {
        CustomerResponse c = createCustomerWithEmail("Eve Tester", "eve+" + UUID.randomUUID() + "@example.com");
        Customer stored = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        // Force the expiry into the past.
        stored.setEmailVerificationExpiresAt(Instant.now().minusSeconds(60));
        customerRepository.save(stored);

        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> customerService.verifyEmailByToken(stored.getEmailVerificationToken()));
        assertTrue(ex.getMessage().toLowerCase().contains("expired"),
                "Error message should mention expiry: " + ex.getMessage());

        Customer after = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        assertNull(after.getEmailVerificationToken(),
                "Expired token must be burned so the same link can't be retried indefinitely");
        assertFalse(after.isEmailVerified(), "Customer must NOT be verified by an expired token");
    }

    @Test
    void resendVerificationOnAlreadyVerifiedCustomerIsIdempotent() {
        CustomerResponse c = createCustomerWithEmail("Frank Tester", "frank+" + UUID.randomUUID() + "@example.com");
        Customer stored = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        customerService.verifyEmailByToken(stored.getEmailVerificationToken());

        CustomerVerificationStatus status = customerService.resendVerification(c.id());
        assertTrue(status.emailVerified(), "Resend on verified customer must report verified=true");
        assertNull(status.emailVerificationExpiresAt(), "Resend on verified customer must not issue a token");

        Customer stillVerified = customerRepository.findByIdAndDeletedAtIsNull(c.id()).orElseThrow();
        assertTrue(stillVerified.isEmailVerified());
        assertNull(stillVerified.getEmailVerificationToken());
    }
}
