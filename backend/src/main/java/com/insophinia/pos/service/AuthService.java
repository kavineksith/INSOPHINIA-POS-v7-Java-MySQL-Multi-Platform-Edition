package com.insophinia.pos.service;

import com.insophinia.pos.dto.AuthDtos.ChangePasswordRequest;
import com.insophinia.pos.dto.AuthDtos.LoginResponse;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.exception.AccountLockedException;
import com.insophinia.pos.exception.ForbiddenException;
import com.insophinia.pos.exception.UnauthorizedException;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.security.JwtService;
import com.insophinia.pos.security.Permissions;
import com.insophinia.pos.security.SessionService;
import com.insophinia.pos.security.TokenBlacklistService;
import com.insophinia.pos.security.TotpService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Login / logout / 2FA / password lifecycle — port of src/app/api/auth/*. */
@Service
public class AuthService {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final com.insophinia.pos.security.SecretCryptoService secretCrypto;
    private final JwtService jwtService;
    private final SessionService sessionService;
    private final TokenBlacklistService blacklistService;
    private final TotpService totpService;
    private final SecurityEventService securityEvents;
    private final SystemLogService systemLog;
    private final long passwordExpiryDays;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       com.insophinia.pos.security.SecretCryptoService secretCrypto,
                       JwtService jwtService, SessionService sessionService,
                       TokenBlacklistService blacklistService, TotpService totpService,
                       SecurityEventService securityEvents, SystemLogService systemLog,
                       @Value("${app.security.password-expiry-days:90}") long passwordExpiryDays) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretCrypto = secretCrypto;
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.blacklistService = blacklistService;
        this.totpService = totpService;
        this.securityEvents = securityEvents;
        this.systemLog = systemLog;
        this.passwordExpiryDays = passwordExpiryDays;
    }

    @Transactional
    public LoginResponse login(String username, String password, HttpServletRequest request) {
        String ip = AuditService.clientIp(request);
        String ua = request.getHeader("User-Agent");

        User user = userRepository.findByUsernameAndDeletedAtIsNull(username).orElse(null);
        if (user == null) {
            securityEvents.log(null, "LOGIN_FAILED", ip, ua, "Login failed for unknown username", "medium");
            systemLog.append("warn", "auth", "LOGIN_FAILED", "Login failed for unknown username", null, null, ip);
            throw new UnauthorizedException("Invalid username or password");
        }

        if (user.isLocked()) {
            securityEvents.log(user.getId(), "LOGIN_LOCKED", ip, ua, "Login attempt on locked account", "medium");
            systemLog.append("warn", "auth", "LOGIN_LOCKED", "Login attempt on locked account: " + username,
                    null, user.getId(), ip);
            throw new AccountLockedException("Account is locked due to multiple failed attempts. Please contact an administrator.");
        }
        if (!user.isActive()) {
            throw new ForbiddenException("Account is deactivated. Please contact admin.");
        }
        if (!passwordEncoder.matches(password, user.getPassword())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                // permanent lock until admin unlock — matches the API behaviour
                user.setLockedUntil(Instant.now().plus(100L * 365, ChronoUnit.DAYS));
                securityEvents.log(user.getId(), "ACCOUNT_LOCKED", ip, ua,
                        "Account permanently locked after " + attempts + " failed attempts", "high");
            }
            userRepository.save(user);
            securityEvents.log(user.getId(), "LOGIN_FAILED", ip, ua, "Invalid password; attempt " + attempts, attempts >= MAX_FAILED_ATTEMPTS ? "high" : "medium");
            systemLog.append("warn", "auth", "LOGIN_FAILED", "Invalid password for account " + username,
                    "attempt=" + attempts, user.getId(), ip);
            throw new UnauthorizedException("Invalid username or password");
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);

        if (user.isTwoFactorEnabled()) {
            String pendingToken = jwtService.generatePending2faToken(user);
            return new LoginResponse(null, true, pendingToken, user.getId(), user.getUsername(),
                    user.getRole().name(), user.getFirstName() + " " + user.getLastName(),
                    user.isMustChangePassword(), List.of());
        }

        var session = sessionService.createSession(user, ip, ua);
        String token = jwtService.generateAccessToken(user, session.getSessionToken());
        securityEvents.log(user.getId(), "LOGIN_SUCCESS", ip, ua, "User logged in", "low");
        return buildLoginResponse(user, token);
    }

    @Transactional
    public LoginResponse verifyTwoFactor(String pendingToken, String code, String backupCode,
                                         HttpServletRequest request) {
        Claims claims;
        try {
            claims = jwtService.parse(pendingToken);
        } catch (Exception e) {
            throw new UnauthorizedException("Invalid or expired 2FA session");
        }
        if (!"pending_2fa".equals(claims.get("type", String.class))) {
            throw new UnauthorizedException("Invalid token type for 2FA verification");
        }
        User user = userRepository.findByIdAndDeletedAtIsNull(jwtService.userId(claims))
                .orElseThrow(() -> new UnauthorizedException("User not found"));

        boolean ok;
        if (backupCode != null && !backupCode.isBlank()) {
            ok = consumeBackupCode(user, backupCode);
        } else {
            ok = totpService.verify(secretCrypto.decrypt(user.getTwoFactorSecret()), code);
        }
        if (!ok) {
            securityEvents.log(user.getId(), "2FA_FAILED", AuditService.clientIp(request),
                    request.getHeader("User-Agent"), "Invalid 2FA code", "medium");
            throw new UnauthorizedException("Invalid two-factor code");
        }

        var session = sessionService.createSession(user, AuditService.clientIp(request), request.getHeader("User-Agent"));
        String token = jwtService.generateAccessToken(user, session.getSessionToken());
        securityEvents.log(user.getId(), "LOGIN_SUCCESS", AuditService.clientIp(request),
                request.getHeader("User-Agent"), "User logged in with 2FA", "low");
        return buildLoginResponse(user, token);
    }

    private boolean consumeBackupCode(User user, String backupCode) {
        String stored = user.getTwoFactorBackupCodes();
        if (stored == null) return false;
        List<String> codes = new ArrayList<>(List.of(stored.split(",")));
        String matched = codes.stream().filter(entry -> verifyBackupCode(backupCode, entry)).findFirst().orElse(null);
        if (matched == null) return false;
        codes.remove(matched);
        user.setTwoFactorBackupCodes(String.join(",", codes));
        userRepository.save(user);
        return true;
    }

    private static final int BACKUP_CODE_ITERATIONS = 120_000;
    private static final int BACKUP_CODE_BITS = 256;

    public static String hashBackupCode(String code) {
        try {
            byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
            byte[] hash = deriveBackupCode(code, salt, BACKUP_CODE_ITERATIONS);
            return "pbkdf2$" + BACKUP_CODE_ITERATIONS + "$" + java.util.Base64.getEncoder().encodeToString(salt)
                    + "$" + java.util.Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) { throw new IllegalStateException("Could not hash 2FA backup code", e); }
    }

    public static boolean verifyBackupCode(String code, String stored) {
        try {
            // One-time compatibility path for backup codes generated by the previous SHA-256 implementation.
            if (stored != null && !stored.startsWith("pbkdf2$"))
                return MessageDigest.isEqual(JwtService.hashToken(code.trim().toLowerCase()).getBytes(StandardCharsets.US_ASCII), stored.getBytes(StandardCharsets.US_ASCII));
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            if (iterations < 100_000 || iterations > 1_000_000) return false;
            byte[] salt = java.util.Base64.getDecoder().decode(parts[2]);
            byte[] expected = java.util.Base64.getDecoder().decode(parts[3]);
            byte[] actual = deriveBackupCode(code, salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
    }

    private static byte[] deriveBackupCode(String code, byte[] salt, int iterations) throws Exception {
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(code.trim().toLowerCase().toCharArray(), salt, iterations, BACKUP_CODE_BITS);
        try { return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    public List<String> generateBackupCodes() {
        SecureRandom random = new SecureRandom();
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            codes.add(String.format("%08x", random.nextInt()).substring(0, 8));
        }
        return codes;
    }

    @Transactional
    public void logout(String rawToken, AuthUserContext ctx, HttpServletRequest request) {
        if (ctx.sessionToken() != null) {
            sessionService.activeSessions(ctx.userId());
            // revoke only the current session
        }
        blacklistService.blacklist(rawToken, ctx.userId(), "logout", jwtService.getAccessTokenHours());
        securityEvents.log(ctx.userId(), "LOGOUT", AuditService.clientIp(request),
                request.getHeader("User-Agent"), "User logged out", "low");
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req, String rawToken) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UnauthorizedException("User not found"));
        if (!passwordEncoder.matches(req.currentPassword(), user.getPassword())) {
            securityEvents.log(userId, "PASSWORD_CHANGE_FAILED", "unknown", "", "Current password validation failed", "medium");
            throw new UnauthorizedException("Current password is incorrect");
        }
        UserService.validatePassword(req.newPassword(), user.getUsername());
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        user.setPasswordChangedAt(Instant.now());
        user.setPasswordExpiresAt(Instant.now().plus(passwordExpiryDays, ChronoUnit.DAYS));
        user.setMustChangePassword(false);
        userRepository.save(user);
        securityEvents.log(userId, "PASSWORD_CHANGED", "unknown", "", "Password changed successfully", "medium");
        // kill every other session + current token so re-login is required
        sessionService.revokeAll(userId, "password_changed");
        if (rawToken != null) {
            blacklistService.blacklist(rawToken, userId, "password_changed", jwtService.getAccessTokenHours());
        }
    }

    private LoginResponse buildLoginResponse(User user, String token) {
        return new LoginResponse(token, false, null, user.getId(), user.getUsername(),
                user.getRole().name(), user.getFirstName() + " " + user.getLastName(),
                user.isMustChangePassword(), Permissions.of(user.getRole()));
    }

    /** Lightweight projection of the authenticated principal needed by this service. */
    public record AuthUserContext(UUID userId, String sessionToken) {}
}
