package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "system_logs")
public class SystemLog extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String level;

    @Column(nullable = false, length = 100)
    private String category;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Column(name = "metadata", columnDefinition = "TEXT")
    private String metadata;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "ip_address", length = 200)
    private String ipAddress;
}
