package com.insophinia.pos.repository;

import com.insophinia.pos.entity.MarketingCampaign;
import com.insophinia.pos.entity.MarketingCampaignStatus;
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

public interface MarketingCampaignRepository extends JpaRepository<MarketingCampaign, UUID>, JpaSpecificationExecutor<MarketingCampaign> {
    Optional<MarketingCampaign> findByIdAndDeletedAtIsNull(UUID id);

    Page<MarketingCampaign> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(MarketingCampaignStatus status, Pageable pageable);

    /**
     * Campaigns that are scheduled to send at-or-before the given instant
     * and haven't started yet. Used by the marketing scheduler job.
     */
    @Query("select c from MarketingCampaign c " +
            "where c.deletedAt is null " +
            "and c.status = com.insophinia.pos.entity.MarketingCampaignStatus.scheduled " +
            "and c.scheduledAt is not null and c.scheduledAt <= :now " +
            "order by c.scheduledAt asc")
    List<MarketingCampaign> findDueForSend(@Param("now") Instant now);

    long countByStatusAndDeletedAtIsNull(MarketingCampaignStatus status);
}
