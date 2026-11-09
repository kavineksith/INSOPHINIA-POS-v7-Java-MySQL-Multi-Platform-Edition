package com.insophinia.pos.repository;

import com.insophinia.pos.entity.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.UUID;

public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, UUID> {
    boolean existsByTokenHash(String tokenHash);
    long deleteByExpiresAtBefore(Instant instant);
}
