package com.insophinia.pos.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "bills")
public class Bill extends BaseEntity {

    @Column(name = "bill_number", nullable = false, unique = true, length = 50)
    private String billNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name", nullable = false)
    private String customerName = "Customer";

    @Column(name = "customer_email")
    private String customerEmail;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id", nullable = false)
    private User staff;

    @Column(name = "staff_name", nullable = false)
    private String staffName;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time")
    private Instant endTime;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "total_discount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDiscount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "sscl_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal ssclAmount = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 10, scale = 2)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "paid_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal paidAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal balance = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillStatus status = BillStatus.pending;

    @Column(name = "return_reason", columnDefinition = "TEXT")
    private String returnReason;

    @Column(name = "return_authorized_by")
    private UUID returnAuthorizedBy;

    @Column(name = "return_authorized_at")
    private Instant returnAuthorizedAt;

    @Column(columnDefinition = "TEXT")
    private String remarks;

    @Column(name = "is_printed", nullable = false)
    private boolean printed = false;

    @Column(name = "is_email_sent", nullable = false)
    private boolean emailSent = false;

    @Column(name = "earned_points", nullable = false)
    private int earnedPoints = 0;

    @Column(name = "points_redeemed", nullable = false)
    private int pointsRedeemed = 0;

    @Column(name = "points_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal pointsValue = BigDecimal.ZERO;

    @Column(name = "points_authorized_by")
    private String pointsAuthorizedBy;

    @Column(name = "points_authorized_at")
    private Instant pointsAuthorizedAt;

    @Column(name = "total_points_after", nullable = false)
    private int totalPointsAfter = 0;

    @OneToMany(mappedBy = "bill", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BillItem> billItems = new ArrayList<>();
}
