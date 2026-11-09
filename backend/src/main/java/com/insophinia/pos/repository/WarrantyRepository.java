package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Warranty;
import com.insophinia.pos.entity.WarrantyStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarrantyRepository extends JpaRepository<Warranty, UUID>, JpaSpecificationExecutor<Warranty> {
    Optional<Warranty> findByIdAndDeletedAtIsNull(UUID id);
    List<Warranty> findByBillId(UUID billId);
    List<Warranty> findByBillItemId(UUID billItemId);

    /**
     * Returns all warranties that have NOT been soft-deleted AND whose expiry
     * is within the next {@code withinDays} days AND whose status is active.
     * Used by the daily "expiring warranty" notification job.
     */
    @Query("select w from Warranty w where w.deletedAt is null " +
            "and w.status = com.insophinia.pos.entity.WarrantyStatus.active " +
            "and w.warrantyExpiresAt between :now and :cutoff " +
            "order by w.warrantyExpiresAt asc")
    List<Warranty> findExpiringWithin(@Param("now") Instant now, @Param("cutoff") Instant cutoff);

    /**
     * Bulk-update the status of all warranties past their expiry date.
     * Used by the daily cron job.
     */
    @Modifying
    @Query("update Warranty w set w.status = com.insophinia.pos.entity.WarrantyStatus.expired " +
            "where w.deletedAt is null and w.status = com.insophinia.pos.entity.WarrantyStatus.active " +
            "and w.warrantyExpiresAt < :now")
    int markExpired(@Param("now") Instant now);

    /**
     * Mark warranties with N+ open claims as 'claimed' (so the UI surfaces
     * them in the claims worklist). Used by the claim-filed event handler.
     */
    @Modifying
    @Query("update Warranty w set w.status = com.insophinia.pos.entity.WarrantyStatus.claimed " +
            "where w.deletedAt is null and w.status = com.insophinia.pos.entity.WarrantyStatus.active " +
            "and w.id in (select distinct c.warranty.id from WarrantyClaim c where c.deletedAt is null and c.status <> com.insophinia.pos.entity.ClaimStatus.closed)")
    int markClaimedForWarrantiesWithOpenClaims();

    Page<Warranty> findByCustomerIdAndDeletedAtIsNullOrderByWarrantyExpiresAtDesc(UUID customerId, Pageable pageable);

    long countByStatusAndDeletedAtIsNull(WarrantyStatus status);
}
