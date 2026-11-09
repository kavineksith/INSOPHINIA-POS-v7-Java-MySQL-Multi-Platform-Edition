package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "items")
public class Item extends BaseEntity {

    @Column(name = "plu_code", nullable = false, unique = true, length = 50)
    private String pluCode;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(unique = true, length = 100)
    private String barcode;

    @Column(name = "qr_code", unique = true, length = 100)
    private String qrCode;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "cost_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal costPrice = BigDecimal.ZERO;

    @Column(nullable = false, length = 50)
    private String unit = "pcs";

    @Column(name = "stock_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal stockQuantity = BigDecimal.ZERO;

    @Column(name = "total_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal totalQuantity = BigDecimal.ZERO;

    @Column(name = "sell_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal sellQuantity = BigDecimal.ZERO;

    @Column(name = "return_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal returnQuantity = BigDecimal.ZERO;

    @Column(name = "damage_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal damageQuantity = BigDecimal.ZERO;

    @Column(name = "lost_quantity", nullable = false, precision = 10, scale = 3)
    private BigDecimal lostQuantity = BigDecimal.ZERO;

    @Column(name = "low_stock_threshold", nullable = false, precision = 10, scale = 3)
    private BigDecimal lowStockThreshold = new BigDecimal("10");

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "has_discount", nullable = false)
    private boolean hasDiscount = false;

    @Column(name = "discount_percentage", nullable = false, precision = 5, scale = 2)
    private BigDecimal discountPercentage = BigDecimal.ZERO;
}
