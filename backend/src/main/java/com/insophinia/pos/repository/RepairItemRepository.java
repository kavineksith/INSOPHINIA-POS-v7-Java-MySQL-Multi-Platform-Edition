package com.insophinia.pos.repository;

import com.insophinia.pos.entity.RepairItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RepairItemRepository extends JpaRepository<RepairItem, UUID> {
    List<RepairItem> findByRepairIdOrderByCreatedAtAsc(UUID repairId);
}
