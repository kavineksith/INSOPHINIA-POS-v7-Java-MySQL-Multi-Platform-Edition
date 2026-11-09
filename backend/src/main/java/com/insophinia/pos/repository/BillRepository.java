package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BillRepository extends JpaRepository<Bill, UUID>, JpaSpecificationExecutor<Bill> {
    Optional<Bill> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Bill> findByBillNumberAndDeletedAtIsNull(String billNumber);
    boolean existsByBillNumber(String billNumber);
    long countByCreatedAtGreaterThanEqual(Instant startOfDay);

    @EntityGraph(attributePaths = {"billItems"})
    @Query("select b from Bill b where b.id = :id and b.deletedAt is null")
    Optional<Bill> findDetailedById(@Param("id") UUID id);

    Page<Bill> findByDeletedAtIsNull(Pageable pageable);
    Page<Bill> findByDeletedAtIsNullAndStatus(BillStatus status, Pageable pageable);
}
