package com.insophinia.pos.repository;

import com.insophinia.pos.entity.LoginSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LoginSessionRepository extends JpaRepository<LoginSession, UUID> {
    Optional<LoginSession> findBySessionToken(String sessionToken);
    List<LoginSession> findByUserIdAndActiveTrueOrderByLastActivityAtDesc(UUID userId);
    List<LoginSession> findByUserIdAndActiveTrue(UUID userId);
    long deleteByExpiresAtBefore(Instant instant);
}
