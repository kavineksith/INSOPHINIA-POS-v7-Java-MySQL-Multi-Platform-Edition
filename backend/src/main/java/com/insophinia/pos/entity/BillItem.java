package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "bill_items")
public class BillItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "bill_id", nullable = false)
    private Bill bill;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    private Item item;

    @Column(name = "plu_code", nullable = false, length = 50)
    private String pluCode;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(nullable = false, precision = 10, scale = 3)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(nullable = false, length = 50)
    private String unit = "pcs";

    @Column(name = "unit_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "actual_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal actualPrice;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal discount = BigDecimal.ZERO;

    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    // --------------------------------------------------------------------------
    // v7 feature columns — added by Flyway migration V3.
    // --------------------------------------------------------------------------

    /**
     * Self-referential parent for sub-items. A "Website development" parent
     * line can have N child bill items (design / frontend / backend). Sub-items
     * inherit their parent's bill_id; their subtotal is included in the parent's
     * display but NOT double-counted in the bill's {@code subtotal} aggregate
     * (the parent's subtotal is the roll-up of its children).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_bill_item_id")
    private BillItem parentBillItem;

    /**
     * Per-line variant specifications, stored as a JSON document.
     * Example for a computer shop laptop line:
     *   {"RAM":"16GB","Storage":"512GB SSD","Color":"Space Gray","Warranty":"12 months"}
     * Example for a freelance development line:
     *   {"Scope":"5 pages","Revisions included":2,"Tech":"the API + Vercel"}
     *
     * The schema is intentionally free-form — the UI's "specifications" editor
     * builds the key/value pairs; the backend validates that the JSON is
     * syntactically valid and bounded in size (≤ 16 KB per line).
     */
    @Column(columnDefinition = "MEDIUMTEXT")
    private String specifications;

    /**
     * Warranty duration in months. 0 = no warranty (thermal receipts omit
     * this field entirely). A4 receipts render the warranty as "12 months
     * warranty, expires 2026-10-18".
     */
    @Column(name = "warranty_months", nullable = false)
    private int warrantyMonths = 0;

    /**
     * Warranty expiry date — derived from {@code bill.createdAt + warrantyMonths}
     * at bill-creation time so it stays stable even if the bill is re-issued.
     */
    @Column(name = "warranty_expires_at")
    private Instant warrantyExpiresAt;

    /**
     * Number of revision rounds included in the line price. For freelance
     * scope: e.g. "2 revisions included" — additional revisions bill at
     * {@code revisionPrice} each.
     */
    @Column(name = "revision_count", nullable = false)
    private int revisionCount = 0;

    /**
     * Price per additional revision beyond {@code revisionCount}. Default 0
     * for non-freelance items.
     */
    @Column(name = "revision_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal revisionPrice = BigDecimal.ZERO;

    /**
     * Display sort order — parent lines sort by this first, then by id.
     * Sub-items always sort after their parent (their sort_order is local
     * to the parent).
     */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;
}
