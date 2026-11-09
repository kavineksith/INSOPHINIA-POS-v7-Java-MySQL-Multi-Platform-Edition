package com.insophinia.pos;

import com.insophinia.pos.entity.User;
import com.insophinia.pos.entity.UserRole;
import com.insophinia.pos.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the dead-man switch's RBAC contract:
 *
 * When the system is locked by {@link com.insophinia.pos.service.SystemService#setLocked},
 * the {@link com.insophinia.pos.security.SystemLockFilter} must:
 * <ol>
 *   <li>Return HTTP 423 LOCKED for ALL authenticated non-system-control
 *       routes (e.g. GET /api/dashboard).</li>
 *   <li>Allow POST /api/system/lock through only for roles with the
 *       {@code manage:system_lock} permission (master_admin only).</li>
 *   <li>Reject POST /api/system/lock for cashier / supervisor / admin roles
 *       with 423 LOCKED (they can't unlock a system they didn't lock).</li>
 *   <li>Always allow GET /api/system/health, /api/system/status,
 *       /api/auth/login, /api/auth/logout, /api/auth/2fa/verify (so users
 *       can still log in and check status while the system is locked).</li>
 * </ol>
 *
 * RBAC contract under test:
 *   - master_admin has manage:system_lock (only role that can unlock).
 *   - admin / supervisor / cashier do NOT have manage:system_lock — they get
 *     423 LOCKED on every endpoint, including POST /api/system/lock.
 *
 * Test isolation: each test method locks + unlocks the system around its
 * assertions, so the test suite can be re-run safely. A new cashier user is
 * seeded for the test (separate from the DataInitializer-seeded test-master).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SystemLockRbacIntegrationTest {

    @Autowired private TestRestTemplate http;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private static final String MASTER_USERNAME = "test-master";
    private static final String MASTER_PASSWORD = "TestMaster!Password123";
    private static final String CASHIER_USERNAME = "lock-test-cashier";
    private static final String CASHIER_PASSWORD = "CashierTest!456";

    @BeforeEach
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void seedCashier() {
        // Clean up any prior run's cashier
        userRepository.findByUsernameAndDeletedAtIsNull(CASHIER_USERNAME).ifPresent(u -> {
            u.setDeletedAt(Instant.now());
            userRepository.save(u);
        });

        // Seed a fresh cashier for this test run.
        User cashier = new User();
        cashier.setUsername(CASHIER_USERNAME);
        cashier.setEmail("lock-test-cashier@insophinia.local");
        cashier.setFirstName("Lock");
        cashier.setLastName("Cashier");
        cashier.setRole(UserRole.cashier);
        cashier.setPassword(passwordEncoder.encode(CASHIER_PASSWORD));
        cashier.setMustChangePassword(false);
        cashier.setPasswordChangedAt(Instant.now());
        cashier.setPasswordExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        userRepository.save(cashier);
    }

    @AfterEach
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void cleanup() {
        // Ensure the system is unlocked for the next test — even if the test
        // itself failed mid-flow and left a locked state behind.
        try {
            String masterToken = login(MASTER_USERNAME, MASTER_PASSWORD);
            if (masterToken != null) {
                http.exchange("/api/system/lock", HttpMethod.POST,
                        new HttpEntity<>(Map.of("locked", false, "reason", "test cleanup"), authHeaders(masterToken)),
                        Map.class);
            }
        } catch (Exception ignored) { /* best-effort cleanup */ }

        // Soft-delete the test cashier so the next run can re-create it.
        userRepository.findByUsernameAndDeletedAtIsNull(CASHIER_USERNAME).ifPresent(u -> {
            u.setDeletedAt(Instant.now());
            userRepository.save(u);
        });
    }

    @Test
    void masterAdminCanLockAndUnlockSystem() {
        String token = login(MASTER_USERNAME, MASTER_PASSWORD);
        assertNotNull(token);

        // Lock
        ResponseEntity<Map> lockResp = http.exchange(
                "/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", true, "reason", "Integration test lock"), authHeaders(token)),
                Map.class);
        assertEquals(HttpStatus.OK, lockResp.getStatusCode(), "master_admin can lock the system");

        // Verify status reflects locked state
        ResponseEntity<Map> statusResp = http.exchange(
                "/api/system/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
        assertEquals(HttpStatus.OK, statusResp.getStatusCode());
        Map data = (Map) statusResp.getBody().get("data");
        assertEquals(Boolean.TRUE, data.get("locked"), "System status must report locked=true");

        // Unlock
        ResponseEntity<Map> unlockResp = http.exchange(
                "/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", ""), authHeaders(token)),
                Map.class);
        assertEquals(HttpStatus.OK, unlockResp.getStatusCode(), "master_admin can unlock the system");
    }

    @Test
    void lockedSystemReturns423ForNonSystemControlRoutes() {
        String token = login(MASTER_USERNAME, MASTER_PASSWORD);

        // Lock the system
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", true, "reason", "Lock RBAC test"), authHeaders(token)),
                Map.class);

        // Any non-system-control route must return 423 LOCKED
        ResponseEntity<Map> dashboardResp = http.exchange(
                "/api/dashboard", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
        assertEquals(HttpStatus.LOCKED, dashboardResp.getStatusCode(),
                "GET /api/dashboard must return 423 LOCKED while system is locked");
        Map body = dashboardResp.getBody();
        assertNotNull(body);
        assertEquals(Boolean.FALSE, body.get("success"));
        assertEquals("SYSTEM_LOCKED", body.get("code"),
                "Error code must be SYSTEM_LOCKED so the client can show the right UX");

        // POST to a non-system-control route must also return 423
        ResponseEntity<Map> createBillResp = http.exchange(
                "/api/billing", HttpMethod.POST,
                new HttpEntity<>(Map.of("items", java.util.List.of(), "paidAmount", "0.00"), authHeaders(token)),
                Map.class);
        assertEquals(HttpStatus.LOCKED, createBillResp.getStatusCode(),
                "POST /api/billing must return 423 LOCKED while system is locked");

        // Cleanup: unlock for the next test (the @AfterEach also does this
        // but doing it explicitly keeps the assertion order clear).
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", ""), authHeaders(token)),
                Map.class);
    }

    @Test
    void cashierGets423OnLockEndpointAndCannotUnlock() {
        // Master admin locks the system
        String masterToken = login(MASTER_USERNAME, MASTER_PASSWORD);
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", true, "reason", "Lock RBAC cashier test"), authHeaders(masterToken)),
                Map.class);

        // Cashier logs in (login is allowed even when system is locked)
        String cashierToken = login(CASHIER_USERNAME, CASHIER_PASSWORD);
        assertNotNull(cashierToken, "Cashier must be able to log in while system is locked");

        // Cashier tries to unlock — must return 423 because cashier lacks manage:system_lock
        ResponseEntity<Map> unlockAttempt = http.exchange(
                "/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", "cashier unlock attempt"), authHeaders(cashierToken)),
                Map.class);
        assertEquals(HttpStatus.LOCKED, unlockAttempt.getStatusCode(),
                "Cashier must NOT be able to unlock — only master_admin has manage:system_lock");

        // Cashier can't do anything else either — GET /api/dashboard returns 423
        ResponseEntity<Map> dashboardResp = http.exchange(
                "/api/dashboard", HttpMethod.GET,
                new HttpEntity<>(authHeaders(cashierToken)), Map.class);
        assertEquals(HttpStatus.LOCKED, dashboardResp.getStatusCode(),
                "Cashier dashboard request must be 423 LOCKED");

        // Cleanup: master unlocks
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", ""), authHeaders(masterToken)),
                Map.class);
    }

    @Test
    void healthAndStatusRemainReachableWhileLocked() {
        String token = login(MASTER_USERNAME, MASTER_PASSWORD);
        // Lock
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", true, "reason", "Whitelist test"), authHeaders(token)),
                Map.class);

        // GET /api/system/health must be public (no auth header)
        ResponseEntity<String> healthResp = http.getForEntity("/api/system/health", String.class);
        assertEquals(HttpStatus.OK, healthResp.getStatusCode(),
                "Health check must always be reachable, even when system is locked");

        // GET /api/system/status must succeed (whitelisted)
        ResponseEntity<Map> statusResp = http.exchange(
                "/api/system/status", HttpMethod.GET,
                new HttpEntity<>(authHeaders(token)), Map.class);
        assertEquals(HttpStatus.OK, statusResp.getStatusCode(),
                "Status endpoint must be reachable when authenticated, even while locked");

        // Cleanup
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", ""), authHeaders(token)),
                Map.class);
    }

    @Test
    void loginSucceedsWhileSystemIsLocked() {
        String masterToken = login(MASTER_USERNAME, MASTER_PASSWORD);
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", true, "reason", "Login-while-locked test"), authHeaders(masterToken)),
                Map.class);

        // A new login must succeed even while the system is locked — otherwise
        // operators couldn't unlock a system that had been logged out of.
        String freshToken = login(CASHIER_USERNAME, CASHIER_PASSWORD);
        assertNotNull(freshToken, "Login must succeed while system is locked");

        // Cleanup
        http.exchange("/api/system/lock", HttpMethod.POST,
                new HttpEntity<>(Map.of("locked", false, "reason", ""), authHeaders(masterToken)),
                Map.class);
    }

    // ----- helpers ------------------------------------------------------

    private String login(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> req = new HttpEntity<>(
                Map.of("username", username, "password", password), headers);
        ResponseEntity<Map> resp = http.postForEntity("/api/auth/login", req, Map.class);
        if (resp.getStatusCode() != HttpStatus.OK) return null;
        Map body = resp.getBody();
        if (body == null) return null;
        Object data = body.get("data");
        if (data instanceof Map) {
            Object token = ((Map<?, ?>) data).get("token");
            if (token != null) return token.toString();
        }
        Object token = body.get("token");
        return token == null ? null : token.toString();
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(token);
        return h;
    }
}
