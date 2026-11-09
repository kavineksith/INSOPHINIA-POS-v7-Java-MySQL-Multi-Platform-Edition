package com.insophinia.pos.repository;

import com.insophinia.pos.entity.ClaimStatus;
import com.insophinia.pos.entity.WarrantyClaim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WarrantyClaimRepository extends JpaRepository<WarrantyClaim, UUID>, JpaSpecificationExecutor<WarrantyClaim> {
    Optional<WarrantyClaim> findByIdAndDeletedAtIsNull(UUID id);
    List<WarrantyClaim> findByWarrantyIdOrderByFiledAtDesc(UUID warrantyId);
    Page<WarrantyClaim> findByStatusAndDeletedAtIsNull(ClaimStatus status, Pageable pageable);
    long countByWarrantyIdAndDeletedAtIsNull(UUID warrantyId);
    long countByWarrantyIdAndStatusAndDeletedAtIsNull(UUID warrantyId, ClaimStatus status);
}
