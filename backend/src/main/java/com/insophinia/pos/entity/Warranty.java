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
 * Warranty registry — one row per warranty-backed bill item. Auto-promoted
 * on bill creation when {@code BillItem.warrantyMonths > 0}. Can also be
 * manually added for items sold before v7 (legacy inventory import).
 *
 * Status lifecycle:
 *   active   → within warranty period (current date < warranty_expires_at)
 *   expiring → within 30 days of expiry (UI surfacing only)
 *   expired  → past warranty_expires_at
 *   claimed  → one or more open WarrantyClaims exist
 *   void     → manually voided by an admin (fraud / duplicate / etc.)
 */
@Getter
@Setter
@Entity
@Table(name = "warranties")
public class Warranty extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_item_id", nullable = false)
    private BillItem billItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @Column(name = "customer_name", nullable = false)
    private String customerName = "Walk-in";

    @Column(name = "customer_email")
    private String customerEmail;

    @Column(name = "customer_phone", length = 20)
    private String customerPhone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id")
    private Item item;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "plu_code", length = 50)
    private String pluCode;

    /**
     * Optional device serial number — for computer-shop inventory with
     * manufacturer serials. Required for warranty claims on electronics.
     */
    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    @Column(name = "warranty_months", nullable = false)
    private int warrantyMonths = 0;

    @Column(name = "warranty_starts_at", nullable = false)
    private Instant warrantyStartsAt;

    @Column(name = "warranty_expires_at", nullable = false)
    private Instant warrantyExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WarrantyStatus status = WarrantyStatus.active;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String notes;
}
