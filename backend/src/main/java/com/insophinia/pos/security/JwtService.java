package com.insophinia.pos.security;

import com.insophinia.pos.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

/** JWT issuing/verification. Token types: "access", "pending_2fa". */
@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenHours;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.access-token-hours:12}") long accessTokenHours) {
        byte[] raw = secret.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 bytes of high-entropy secret material");
        this.key = Keys.hmacShaKeyFor(raw);
        this.accessTokenHours = accessTokenHours;
    }

    public String generateAccessToken(User user, String sessionToken) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", "access")
                .claim("username", user.getUsername())
                .claim("role", user.getRole().name())
                .claim("session", sessionToken)
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(accessTokenHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /** Short-lived token issued after password check when 2FA is enabled. */
    public String generatePending2faToken(User user) {
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("type", "pending_2fa")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    public boolean isValid(String token) {
        try {
            parse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public UUID userId(Claims claims) {
        return UUID.fromString(claims.getSubject());
    }

    /** SHA-256 hex of the raw token — what is stored in the blacklist (never the raw JWT). */
    public static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public long getAccessTokenHours() { return accessTokenHours; }
}
