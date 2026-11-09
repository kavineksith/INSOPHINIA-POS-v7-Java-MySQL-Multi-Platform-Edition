package com.insophinia.pos.repository;

import com.insophinia.pos.entity.MarketingSendLog;
import com.insophinia.pos.entity.MarketingSendStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MarketingSendLogRepository extends JpaRepository<MarketingSendLog, UUID> {
    Page<MarketingSendLog> findByCampaignIdOrderByCreatedAtAsc(UUID campaignId, Pageable pageable);
    List<MarketingSendLog> findByCampaignIdAndStatus(UUID campaignId, MarketingSendStatus status);
    long countByCampaignId(UUID campaignId);
}
