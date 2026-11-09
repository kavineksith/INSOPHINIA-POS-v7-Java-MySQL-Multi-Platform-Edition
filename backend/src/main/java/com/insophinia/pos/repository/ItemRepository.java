package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Item;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID>, JpaSpecificationExecutor<Item> {
    Optional<Item> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Item> findByPluCodeAndDeletedAtIsNull(String pluCode);
    Optional<Item> findByBarcodeAndDeletedAtIsNull(String barcode);
    boolean existsByPluCode(String pluCode);
    List<Item> findByDeletedAtIsNullAndActiveTrue();
    Page<Item> findByDeletedAtIsNull(Pageable pageable);
    @Query("select count(i) from Item i where i.deletedAt is null and i.active = true and i.stockQuantity <= i.lowStockThreshold")
    long countLowStockLive();
    long countByDeletedAtIsNull();

    List<Item> findByDeletedAtIsNullAndActiveTrueAndStockQuantityLessThanEqual(BigDecimal threshold);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Item i where i.id = :id")
    Optional<Item> findByIdForUpdate(@Param("id") UUID id);

    /** Atomic guarded stock decrement — WHERE stockQuantity >= :qty, fails (0 rows) on cross-device race. */
    @Modifying
    @Query("update Item i set i.stockQuantity = i.stockQuantity - :qty, i.sellQuantity = i.sellQuantity + :qty, "
            + "i.version = i.version + 1, i.updatedAt = :now "
            + "where i.id = :id and i.stockQuantity >= :qty")
    int decrementStock(@Param("id") UUID id, @Param("qty") BigDecimal qty, @Param("now") java.time.Instant now);

    @Modifying
    @Query("update Item i set i.stockQuantity = i.stockQuantity + :qty, i.returnQuantity = i.returnQuantity + :qty, "
            + "i.version = i.version + 1, i.updatedAt = :now where i.id = :id")
    int incrementStockForReturn(@Param("id") UUID id, @Param("qty") BigDecimal qty, @Param("now") java.time.Instant now);
}
