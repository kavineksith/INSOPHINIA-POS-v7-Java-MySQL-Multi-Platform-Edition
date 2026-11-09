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
 * Repair job — separate section for repair / maintenance work.
 *
 * Used by:
 *   - Computer shops accepting devices for repair
 *   - Freelance developers accepting bug-fix / scope-creep work after delivery
 *   - Service shops (appliance repair, etc.)
 *
 * A repair job can be tied to a Warranty (if the repair is a warranty claim
 * resolution) or to a Bill (the final invoice for the repair work). Both
 * links are optional — a repair can exist standalone.
 *
 * Status workflow:
 *   received          → item dropped off, customer info captured
 *   diagnosed         → technician wrote the diagnosis + estimated_cost
 *   in_progress       → repair work has started
 *   awaiting_parts   → blocked on ordered parts
 *   completed         → repair done, awaiting customer pickup
 *   delivered          → customer picked up
 *   closed             → fully closed (final bill paid)
 *   rejected           → customer declined the diagnosis/estimate
 *   cancelled          → customer cancelled before completion
 */
@Getter
@Setter
@Entity
@Table(name = "repairs")
public class Repair extends BaseEntity {

    @Column(name = "repair_number", nullable = false, length = 50)
    private String repairNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name", nullable = false)
    private String customerName = "Walk-in";

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @Column(name = "item_description", nullable = false, length = 500)
    private String itemDescription;

    @Column(name = "item_serial", length = 100)
    private String itemSerial;

    @Column(name = "problem_reported", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String problemReported;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String diagnosis;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "technician_id")
    private User technician;

    @Column(name = "technician_name")
    private String technicianName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RepairStatus status = RepairStatus.received;

    @Enumerated(EnumType.STRING)
    @Column(name = "priority", nullable = false, length = 20)
    private RepairPriority priority = RepairPriority.normal;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "diagnosed_at")
    private Instant diagnosedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "estimated_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal estimatedCost = BigDecimal.ZERO;

    @Column(name = "actual_cost", nullable = false, precision = 10, scale = 2)
    private BigDecimal actualCost = BigDecimal.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "final_bill_id")
    private Bill finalBill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "warranty_id")
    private Warranty warranty;

    @Column(name = "warranty_months", nullable = false)
    private int warrantyMonths = 0;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String notes;
}
