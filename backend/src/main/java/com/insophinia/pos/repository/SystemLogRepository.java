package com.insophinia.pos.repository;

import com.insophinia.pos.entity.SystemLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.UUID;

public interface SystemLogRepository extends JpaRepository<SystemLog, UUID>, JpaSpecificationExecutor<SystemLog> {
    long deleteByCreatedAtBefore(Instant before);
}
