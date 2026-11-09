package com.insophinia.pos.security;

import com.insophinia.pos.entity.LoginSession;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.repository.LoginSessionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Login-session lifecycle: create, touch, validate, revoke. */
@Service
public class SessionService {

    private final LoginSessionRepository repository;
    private final long sessionHours;
    private final SecureRandom random = new SecureRandom();

    public SessionService(LoginSessionRepository repository,
                          @Value("${app.jwt.access-token-hours:12}") long sessionHours) {
        this.repository = repository;
        this.sessionHours = sessionHours;
    }

    @Transactional
    public LoginSession createSession(User user, String ip, String userAgent) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        LoginSession session = new LoginSession();
        session.setUser(user);
        session.setSessionToken(HexFormat.of().formatHex(bytes));
        session.setIpAddress(ip == null ? "unknown" : ip);
        session.setUserAgent(userAgent == null ? "" : userAgent);
        session.setBrowser(parseBrowser(userAgent));
        session.setOs(parseOs(userAgent));
        session.setExpiresAt(Instant.now().plus(sessionHours, ChronoUnit.HOURS));
        session.setActive(true);
        return repository.save(session);
    }

    @Transactional
    public boolean isSessionActive(String sessionToken) {
        return repository.findBySessionToken(sessionToken)
                .filter(LoginSession::isActive)
                .filter(s -> s.getExpiresAt().isAfter(Instant.now()))
                .map(s -> {
                    s.setLastActivityAt(Instant.now());
                    return true;
                })
                .orElse(false);
    }

    public List<LoginSession> activeSessions(UUID userId) {
        return repository.findByUserIdAndActiveTrueOrderByLastActivityAtDesc(userId);
    }

    @Transactional
    public boolean revoke(UUID sessionId, UUID userId, String reason) {
        return repository.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(userId))
                .map(s -> {
                    s.setActive(false);
                    s.setRevokedAt(Instant.now());
                    s.setRevokeReason(reason);
                    return true;
                })
                .orElse(false);
    }

    @Transactional
    public int revokeAll(UUID userId, String reason) {
        int count = 0;
        for (LoginSession s : repository.findByUserIdAndActiveTrue(userId)) {
            s.setActive(false);
            s.setRevokedAt(Instant.now());
            s.setRevokeReason(reason);
            count++;
        }
        return count;
    }

    private static String parseBrowser(String ua) {
        if (ua == null) return "Unknown";
        if (ua.contains("Edg")) return "Edge";
        if (ua.contains("Chrome")) return "Chrome";
        if (ua.contains("Firefox")) return "Firefox";
        if (ua.contains("Safari")) return "Safari";
        return "Unknown";
    }

    private static String parseOs(String ua) {
        if (ua == null) return "Unknown";
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Mac OS")) return "macOS";
        if (ua.contains("Linux")) return "Linux";
        if (ua.contains("Android")) return "Android";
        if (ua.contains("iPhone") || ua.contains("iPad")) return "iOS";
        return "Unknown";
    }
}
