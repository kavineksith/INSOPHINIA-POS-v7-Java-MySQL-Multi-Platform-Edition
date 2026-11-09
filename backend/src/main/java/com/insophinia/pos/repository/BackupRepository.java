package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Backup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BackupRepository extends JpaRepository<Backup, UUID> {
}
