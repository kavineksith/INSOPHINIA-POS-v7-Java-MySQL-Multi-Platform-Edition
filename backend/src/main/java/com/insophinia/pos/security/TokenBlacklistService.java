package com.insophinia.pos.security;

import com.insophinia.pos.entity.TokenBlacklist;
import com.insophinia.pos.repository.TokenBlacklistRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/** Persists hashes of revoked JWTs; checked on every authenticated request. */
@Service
public class TokenBlacklistService {

    private final TokenBlacklistRepository repository;

    public TokenBlacklistService(TokenBlacklistRepository repository) {
        this.repository = repository;
    }

    public boolean isBlacklisted(String rawToken) {
        return repository.existsByTokenHash(JwtService.hashToken(rawToken));
    }

    @Transactional
    public void blacklist(String rawToken, UUID userId, String reason, long ttlHours) {
        TokenBlacklist entry = new TokenBlacklist();
        entry.setTokenHash(JwtService.hashToken(rawToken));
        entry.setUserId(userId);
        entry.setReason(reason);
        entry.setExpiresAt(Instant.now().plus(ttlHours, ChronoUnit.HOURS));
        repository.save(entry);
    }

    @Scheduled(cron = "0 0 * * * *") // hourly purge of expired entries
    @Transactional
    public void purgeExpired() {
        repository.deleteByExpiresAtBefore(Instant.now());
    }
}
