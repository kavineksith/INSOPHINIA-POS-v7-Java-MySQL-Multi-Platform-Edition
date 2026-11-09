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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Warranty claim — one per filed customer complaint. A warranty can have
 * multiple claims (e.g. recurring faults). Each claim tracks the customer's
 * report + technician's diagnosis + resolution.
 *
 * Resolution types:
 *   pending     → no resolution yet (claim filed, awaiting technician)
 *   repair      → technician fixed the item (no charge if under warranty)
 *   replacement → swapped for a new item
 *   refund      → returned the original; refund issued
 *   rejected    → claim denied (out of warranty / customer-induced damage)
 *   escalated   → escalated to manufacturer / management
 *
 * Status lifecycle:
 *   open         → freshly filed
 *   in_progress  → technician is working on it
 *   resolved     → resolution applied (repair/replacement/refund)
 *   rejected     → denied
 *   closed        → closed after final verification (regardless of resolution)
 */
@Getter
@Setter
@Entity
@Table(name = "warranty_claims")
public class WarrantyClaim extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warranty_id", nullable = false)
    private Warranty warranty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name", nullable = false)
    private String customerName = "Walk-in";

    @Column(name = "claim_number", nullable = false, length = 50)
    private String claimNumber;

    @Column(name = "fault_reported", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String faultReported;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private User technician;

    @Column(name = "technician_name")
    private String technicianName;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String diagnosis;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 30)
    private ClaimResolution resolution = ClaimResolution.pending;

    @Column(name = "resolution_notes", columnDefinition = "MEDIUMTEXT")
    private String resolutionNotes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replacement_item_id")
    private Item replacementItem;

    @Column(name = "refund_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ClaimStatus status = ClaimStatus.open;

    @Column(name = "filed_at", nullable = false)
    private Instant filedAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
