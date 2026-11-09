package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Bulk-email campaign — sends an {@link EmailTemplate} to a filtered
 * customer segment. Stores the template + filter criteria + scheduling +
 * send count + per-recipient send log.
 *
 * {@code targetFilter} is a JSON document describing the customer filter
 * (loyalty_points_min, loyalty_points_max, created_after, created_before,
 * has_email=true, has_phone=true, etc.). Parsed in {@link
 * com.insophinia.pos.service.MarketingService#parseFilter}.
 *
 * Status lifecycle:
 *   draft     → not yet scheduled
 *   scheduled → scheduledAt is set; waiting for the scheduler job
 *   sending   → scheduler picked it up; sends are in flight
 *   sent      → all sends complete (sent_count + failed_count = target_count)
 *   cancelled → operator cancelled before completion
 *   failed    → an unrecoverable error occurred during the send loop
 *
 * {@code snapshotSubject} + {@code snapshotBody} capture the template body
 * at the moment the campaign moved from draft → scheduled. This guarantees
 * historical sends remain reproducible even if the template is later edited.
 */
@Getter
@Setter
@Entity
@Table(name = "marketing_campaigns")
public class MarketingCampaign extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private EmailTemplate template;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarketingCampaignStatus status = MarketingCampaignStatus.draft;

    /**
     * JSON-encoded customer filter document. Schema:
     *   {
     *     "loyaltyPointsMin": 0,
     *     "loyaltyPointsMax": 1000,
     *     "createdAfter": "2026-01-01T00:00:00Z",
     *     "createdBefore": "2026-12-31T23:59:59Z",
     *     "hasEmail": true,
     *     "hasPhone": false,
     *     "emailVerified": true,
     *     "customerIds": ["uuid1", "uuid2"]
     *   }
     * All fields optional — empty filter = all customers.
     */
    @Column(name = "target_filter", columnDefinition = "MEDIUMTEXT")
    private String targetFilter;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "snapshot_subject", nullable = false, length = 255)
    private String snapshotSubject;

    @Column(name = "snapshot_body", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String snapshotBody;

    @Column(name = "target_count", nullable = false)
    private int targetCount = 0;

    @Column(name = "sent_count", nullable = false)
    private int sentCount = 0;

    @Column(name = "failed_count", nullable = false)
    private int failedCount = 0;

    @Column(name = "opened_count", nullable = false)
    private int openedCount = 0;

    @Column(name = "clicked_count", nullable = false)
    private int clickedCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;
}
