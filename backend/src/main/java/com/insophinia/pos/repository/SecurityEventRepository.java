package com.insophinia.pos.repository;

import com.insophinia.pos.entity.SecurityEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.UUID;

public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID>, JpaSpecificationExecutor<SecurityEvent> {
    Page<SecurityEvent> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
