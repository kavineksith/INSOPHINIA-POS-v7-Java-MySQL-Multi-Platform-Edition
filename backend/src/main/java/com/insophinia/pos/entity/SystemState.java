package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "system_state")
public class SystemState extends BaseEntity {

    @Column(name = "is_locked", nullable = false)
    private boolean locked = false;

    @Column(name = "lock_reason", columnDefinition = "TEXT")
    private String lockReason;

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "last_checkin_at", nullable = false)
    private Instant lastCheckinAt = Instant.now();

    @Column(name = "deadman_days", nullable = false)
    private int deadmanDays = 72;
}
