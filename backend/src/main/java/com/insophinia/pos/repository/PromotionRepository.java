package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Promotion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PromotionRepository extends JpaRepository<Promotion, UUID>, JpaSpecificationExecutor<Promotion> {
    Optional<Promotion> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Promotion> findByNameAndDeletedAtIsNull(String name);
    Page<Promotion> findByDeletedAtIsNull(Pageable pageable);

    @Query("select p from Promotion p left join fetch p.item left join fetch p.category "
            + "where p.deletedAt is null and p.active = true and p.startDate <= :now and p.endDate >= :now")
    List<Promotion> findActiveAt(@Param("now") Instant now);
}
