package com.insophinia.pos.repository;

import com.insophinia.pos.entity.StockMovement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.UUID;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID>, JpaSpecificationExecutor<StockMovement> {
    Page<StockMovement> findByItemIdOrderByMovementDateDesc(UUID itemId, Pageable pageable);

    /** Used by concurrency tests to assert all stock-out movements tied to an item. */
    List<StockMovement> findByItemId(UUID itemId);

    /** Bulk-delete all movements tied to a particular bill (reference_id). */
    @Modifying
    @org.springframework.data.jpa.repository.Query("delete from StockMovement s where s.referenceId = :referenceId")
    int deleteByReferenceId(@org.springframework.data.repository.query.Param("referenceId") UUID referenceId);

    /** Bulk-delete all movements tied to a particular item. */
    @Modifying
    @org.springframework.data.jpa.repository.Query("delete from StockMovement s where s.item.id = :itemId")
    int deleteByItemId(@org.springframework.data.repository.query.Param("itemId") UUID itemId);
}
