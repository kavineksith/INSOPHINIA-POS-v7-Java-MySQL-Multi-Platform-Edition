package com.insophinia.pos;

// ApiResponse not imported — we use Map for the HTTP response body so the test
// doesn't bind to the record's accessor naming (success()/data() vs isSuccess()/getData()).

import com.insophinia.pos.entity.User;
import com.insophinia.pos.repository.UserRepository;
import dev.samstevens.totp.code.CodeGenerator;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that locks in the contract of the 2FA lifecycle:
 *
 *   1. POST /api/auth/2fa/setup  with {password}          → 200 + {secret, otpauthUrl, backupCodes}
 *   2. POST /api/auth/2fa/enable with {code} (valid TOTP)  → 200 (2FA flag flipped on)
 *   3. POST /api/auth/2fa/setup  again                     → 400 "already enabled"
 *   4. POST /api/auth/2fa/disable with {password}          → 200 (2FA flag flipped off)
 *
 * Bootstraps the full Spring context against a Testcontainers MySQL 8 instance
 * (per application-test.yml). The DataInitializer seeds `test-master` (the
 * master_admin) with the password from app.seed.admin-password. We reset the
 * seeded user's 2FA state in {@link #resetTwoFactorState()} so each test
 * starts from a clean baseline.
 *
 * The test generates a real TOTP code using the same `dev.samstevens.totp`
 * library that {@link com.insophinia.pos.security.TotpService} uses, so the
 * verification logic exercised is byte-for-byte identical to what production
 * would do for a real user.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TwoFactorLifecycleIntegrationTest {

    @Autowired private TestRestTemplate http;
    @Autowired private UserRepository userRepository;

    // Test-master credentials come from application-test.yml. They are
    // configured as test-only constants; never reuse them in production.
    private static final String TEST_MASTER_USERNAME = "test-master";
    private static final String TEST_MASTER_PASSWORD = "TestMaster!Password123";

    // Same code generator config as TotpService — SHA1, 6 digits, 30s period.
    private final CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);

    @BeforeEach
    @Transactional
    void resetTwoFactorState() {
        // Some test runs seed the master_admin with mustChangePassword=true; we
        // flip that off so the login JWT isn't immediately forced into a
        // /change-password redirect flow that would interfere with our 2FA
        // assertions.
        userRepository.findByUsernameAndDeletedAtIsNull(TEST_MASTER_USERNAME).ifPresent(u -> {
            u.setTwoFactorEnabled(false);
            u.setTwoFactorSecret(null);
            u.setTwoFactorBackupCodes(null);
            u.setMustChangePassword(false);
            u.setPasswordChangedAt(Instant.now());
            u.setPasswordExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
            userRepository.save(u);
        });
    }

    @Test
    void twoFactorSetupEnableDisableRoundTrip() {
        // 1) Login as the seeded master_admin.
        String token = loginAsTestMaster();
        assertNotNull(token, "Login must succeed and return a JWT");

        HttpHeaders auth = authHeaders(token);

        // 2) /api/auth/2fa/setup — must return the full setup payload.
        ResponseEntity<Map> setupResp = http.exchange(
                "/api/auth/2fa/setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", TEST_MASTER_PASSWORD), auth),
                Map.class);
        assertEquals(HttpStatus.OK, setupResp.getStatusCode(), "setup must return 200 OK with correct password");
        assertNotNull(setupResp.getBody(), "setup response body must be present");

        // ApiResponse envelopes the payload under `data`. We round-trip through
        // Map to avoid coupling the test to a specific DTO binding strategy.
        Map<String, Object> setupData = (Map<String, Object>) setupResp.getBody().get("data");
        assertNotNull(setupData, "setup response data must be non-null");

        String secret = (String) setupData.get("secret");
        String otpauthUrl = (String) setupData.get("otpauthUrl");
        Object backupCodesRaw = setupData.get("backupCodes");

        assertNotNull(secret, "setup must return the raw TOTP secret");
        assertFalse(secret.isBlank(), "secret must not be blank");
        assertNotNull(otpauthUrl, "setup must return the otpauth:// URI for QR rendering");
        assertTrue(otpauthUrl.startsWith("otpauth://totp/"),
                "otpauthUrl must be a totp URI; got: " + otpauthUrl);
        assertTrue(otpauthUrl.contains("secret=" + secret),
                "otpauthUrl must contain the same secret that was returned in the clear");
        assertNotNull(backupCodesRaw, "setup must return backup codes");
        // The response payload's `backupCodes` field is a List<String> with 8 entries.
        assertTrue(backupCodesRaw instanceof java.util.List<?>);
        assertEquals(8, ((java.util.List<?>) backupCodesRaw).size(),
                "setup must return exactly 8 backup codes (consistency with the specification)");

        // 3) /api/auth/2fa/enable — generate a real TOTP code from the secret
        //    and submit it. Must flip the user's twoFactorEnabled flag on.
        String validCode = generateTotpCode(secret);
        ResponseEntity<Map> enableResp = http.exchange(
                "/api/auth/2fa/enable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", validCode), auth),
                Map.class);
        assertEquals(HttpStatus.OK, enableResp.getStatusCode(),
                "enable must return 200 OK when the TOTP code matches the saved secret");
        assertTrue(Boolean.TRUE.equals(enableResp.getBody().get("success")),
                "enable response success flag must be true");

        // 4) Verify the user record now has 2FA enabled, the secret is still
        //    on file (encrypted), and the backup-code hashes were stored.
        User enabledUser = userRepository.findByUsernameAndDeletedAtIsNull(TEST_MASTER_USERNAME)
                .orElseThrow(() -> new AssertionError("test-master should still exist"));
        assertTrue(enabledUser.isTwoFactorEnabled(),
                "User.twoFactorEnabled must be true after a successful /enable call");
        assertNotNull(enabledUser.getTwoFactorSecret(),
                "TwoFactor secret must remain persisted (encrypted at rest) so future logins can verify");
        assertNotNull(enabledUser.getTwoFactorBackupCodes(),
                "Backup-code hashes must be persisted so users can recover from a lost device");

        // 5) /api/auth/2fa/setup called a second time must be rejected with
        //    400 BAD_REQUEST — "already enabled". This is a contract guarantee:
        //    the user must explicitly disable 2FA before re-setting-up.
        ResponseEntity<Map> secondSetup = http.exchange(
                "/api/auth/2fa/setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", TEST_MASTER_PASSWORD), auth),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, secondSetup.getStatusCode(),
                "Re-running setup while 2FA is enabled must return 400 to prevent secret overwrite races");

        // 6) /api/auth/2fa/disable — must flip 2FA off when the correct
        //    password is supplied.
        ResponseEntity<Map> disableResp = http.exchange(
                "/api/auth/2fa/disable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", TEST_MASTER_PASSWORD), auth),
                Map.class);
        assertEquals(HttpStatus.OK, disableResp.getStatusCode(),
                "disable must return 200 OK with correct password");
        assertTrue(Boolean.TRUE.equals(disableResp.getBody().get("success")));

        // 7) Verify the user record now has 2FA disabled.
        User disabledUser = userRepository.findByUsernameAndDeletedAtIsNull(TEST_MASTER_USERNAME).orElseThrow();
        assertFalse(disabledUser.isTwoFactorEnabled(),
                "User.twoFactorEnabled must be false after a successful /disable call");
    }

    @Test
    void setupRejectsWrongPassword() {
        String token = loginAsTestMaster();
        HttpHeaders auth = authHeaders(token);

        // Send a deliberately wrong password — setup must return 401.
        ResponseEntity<Map> resp = http.exchange(
                "/api/auth/2fa/setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "WrongPassword!123"), auth),
                Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "setup with wrong password must return 401 (not 200 + empty body) — security boundary");
    }

    @Test
    void enableRejectsInvalidCode() {
        String token = loginAsTestMaster();
        HttpHeaders auth = authHeaders(token);

        // Run setup to get a fresh secret.
        ResponseEntity<Map> setupResp = http.exchange(
                "/api/auth/2fa/setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", TEST_MASTER_PASSWORD), auth),
                Map.class);
        assertEquals(HttpStatus.OK, setupResp.getStatusCode());

        // Submit a deliberately wrong 6-digit code — enable must return 401.
        ResponseEntity<Map> enableResp = http.exchange(
                "/api/auth/2fa/enable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", "000000"), auth),
                Map.class);
        // 000000 has a 1/1,000,000 chance of being valid for any 30s window.
        // If the test ever flaps here, regenerate the secret or wait for the
        // next TOTP window.
        assertEquals(HttpStatus.UNAUTHORIZED, enableResp.getStatusCode(),
                "enable with an invalid code must return 401, not flip 2FA on");

        // Sanity-check: user is still not enabled.
        User u = userRepository.findByUsernameAndDeletedAtIsNull(TEST_MASTER_USERNAME).orElseThrow();
        assertFalse(u.isTwoFactorEnabled(),
                "User must NOT be 2FA-enabled when enable failed verification");
    }

    @Test
    void enableWithoutPriorSetupReturnsBadRequest() {
        String token = loginAsTestMaster();
        HttpHeaders auth = authHeaders(token);

        // Fresh state from @BeforeEach — no secret saved.
        String arbitraryCode = generateTotpCode("JBSWY3DPEHPK3PXP"); // any base32 secret
        ResponseEntity<Map> resp = http.exchange(
                "/api/auth/2fa/enable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", arbitraryCode), auth),
                Map.class);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode(),
                "enable called before setup must return 400 — the user must run setup first");
    }

    @Test
    void disableRejectsWrongPassword() {
        // Pre-enable 2FA by running setup + enable directly via the service
        // layer, so we can isolate the disable-password-check contract.
        String token = loginAsTestMaster();
        HttpHeaders auth = authHeaders(token);

        ResponseEntity<Map> setupResp = http.exchange(
                "/api/auth/2fa/setup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", TEST_MASTER_PASSWORD), auth),
                Map.class);
        assertEquals(HttpStatus.OK, setupResp.getStatusCode());
        Map<String, Object> setupData = (Map<String, Object>) setupResp.getBody().get("data");
        String secret = (String) setupData.get("secret");

        ResponseEntity<Map> enableResp = http.exchange(
                "/api/auth/2fa/enable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("code", generateTotpCode(secret)), auth),
                Map.class);
        assertEquals(HttpStatus.OK, enableResp.getStatusCode());

        // Now disable with a wrong password — must return 401.
        ResponseEntity<Map> disableResp = http.exchange(
                "/api/auth/2fa/disable",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("password", "WrongPassword!123"), auth),
                Map.class);
        assertEquals(HttpStatus.UNAUTHORIZED, disableResp.getStatusCode(),
                "disable with wrong password must return 401, not actually disable 2FA");
        User stillEnabled = userRepository.findByUsernameAndDeletedAtIsNull(TEST_MASTER_USERNAME).orElseThrow();
        assertTrue(stillEnabled.isTwoFactorEnabled(),
                "2FA must remain enabled when disable rejects the password");
    }

    // ----- helpers -------------------------------------------------------

    private String loginAsTestMaster() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, String>> req = new HttpEntity<>(
                Map.of("username", TEST_MASTER_USERNAME, "password", TEST_MASTER_PASSWORD), headers);
        ResponseEntity<Map> resp = http.postForEntity("/api/auth/login", req, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(), "login must succeed for the seeded test-master");
        Map body = resp.getBody();
        assertNotNull(body);
        // The login response shape is { success, data: { token, ... } } OR
        // { success, token, ... } depending on the ApiResponse envelope mode
        // in use. Handle both shapes defensively.
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

    /**
     * Generate a real, currently-valid 6-digit TOTP code for the given Base32
     * secret, using the same library ({@link dev.samstevens.totp}) and
     * algorithm (SHA1, 6 digits, 30s period) as production {@link
     * com.insophinia.pos.security.TotpService}.
     */
    private String generateTotpCode(String secret) {
        try {
            long currentBucket = new SystemTimeProvider().getTime() / 30L;
            return codeGenerator.generate(secret, currentBucket);
        } catch (CodeGenerationException e) {
            // CodeGenerator.generate throws CodeGenerationException only for
            // malformed Base32 secrets; our test secrets come straight from
            // TotpService.generateSecret() so this is unreachable in practice.
            throw new AssertionError("Failed to generate TOTP code for test secret", e);
        }
    }
}
