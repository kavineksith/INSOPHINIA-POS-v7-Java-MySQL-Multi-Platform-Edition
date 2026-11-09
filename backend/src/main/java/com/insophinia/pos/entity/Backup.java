package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "backups")
public class Backup extends BaseEntity {

    @Column(nullable = false)
    private String filename;

    @Column(name = "file_path", nullable = false, length = 512)
    private String filePath = "";

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Enumerated(EnumType.STRING)
    @Column(name = "backup_type", nullable = false, length = 20)
    private BackupType backupType;

    @Column(name = "created_by")
    private UUID createdBy;
}
