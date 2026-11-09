package com.insophinia.pos.repository;

import com.insophinia.pos.entity.SystemState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SystemStateRepository extends JpaRepository<SystemState, UUID> {
}
