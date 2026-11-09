package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Repair;
import com.insophinia.pos.entity.RepairStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RepairRepository extends JpaRepository<Repair, UUID>, JpaSpecificationExecutor<Repair> {
    Optional<Repair> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Repair> findByRepairNumberAndDeletedAtIsNull(String repairNumber);

    Page<Repair> findByStatusAndDeletedAtIsNull(RepairStatus status, Pageable pageable);
    Page<Repair> findByCustomerIdAndDeletedAtIsNullOrderByReceivedAtDesc(UUID customerId, Pageable pageable);
    Page<Repair> findByTechnicianIdAndDeletedAtIsNull(UUID technicianId, Pageable pageable);

    long countByStatusAndDeletedAtIsNull(RepairStatus status);
    long countByDeletedAtIsNull();
    long countByReceivedAtAfterAndDeletedAtIsNull(Instant since);
    long countByPriorityAndDeletedAtIsNull(com.insophinia.pos.entity.RepairPriority priority);

    /**
     * Repairs that have been in their current status longer than the SL —
     * used by the SLA-breach notification job.
     */
    List<Repair> findByStatusInAndPriorityAndDeletedAtIsNull(
            List<RepairStatus> statuses, com.insophinia.pos.entity.RepairPriority priority);
}
