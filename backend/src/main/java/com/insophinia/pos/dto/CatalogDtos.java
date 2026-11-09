package com.insophinia.pos.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class CatalogDtos {
    private CatalogDtos() {}

    public record CategoryRequest(@NotBlank @Size(max = 120) String name, @Size(max = 2000) String description, Boolean active) {}
    public record CategoryResponse(UUID id, String name, String description, boolean active,
                                   Instant createdAt, Instant updatedAt) {}

    public record ItemRequest(
            @NotBlank @Size(max = 50) String pluCode,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 2000) String description,
            @NotNull UUID categoryId,
            String barcode,
            String qrCode,
            @NotNull @DecimalMin("0") BigDecimal price,
            @DecimalMin("0") BigDecimal costPrice,
            String unit,
            @DecimalMin("0") BigDecimal stockQuantity,
            @DecimalMin("0") BigDecimal lowStockThreshold,
            Boolean active,
            Boolean hasDiscount,
            @DecimalMin("0") BigDecimal discountPercentage) {}

    public record ItemResponse(UUID id, String pluCode, String name, String description,
                               UUID categoryId, String categoryName, String barcode, String qrCode,
                               BigDecimal price, BigDecimal costPrice, String unit,
                               BigDecimal stockQuantity, BigDecimal lowStockThreshold,
                               int version, boolean active, boolean hasDiscount, BigDecimal discountPercentage,
                               Instant createdAt, Instant updatedAt) {}

    public record PromotionRequest(
            UUID itemId, UUID categoryId, @NotBlank @Size(max = 120) String name, @Size(max = 2000) String description,
            @NotBlank String discountType, @NotNull @DecimalMin("0") BigDecimal discountValue,
            @NotNull Instant startDate, @NotNull Instant endDate, Boolean active, @Min(1) Integer minItemCount) {}

    public record PromotionResponse(UUID id, UUID itemId, String itemName, UUID categoryId, String categoryName,
                                    String name, String description, String discountType, BigDecimal discountValue,
                                    Instant startDate, Instant endDate, boolean active, int minItemCount) {}

    public record CustomerRequest(@NotBlank @Size(max = 120) String name, @Email @Size(max = 254) String email, @Size(max = 20) String phone) {}

    public record CustomerResponse(UUID id, String name, String email, String phone,
                                   int loyaltyPoints, boolean emailVerified, Instant createdAt) {}

    /** Returned by POST /api/customers/{id}/resend-verification. */
    public record CustomerVerificationStatus(UUID customerId, String email, boolean emailVerified,
                                             Instant emailVerificationExpiresAt) {}

    /** Returned by GET /api/customers/verify-email?token=... on success. */
    public record CustomerVerificationResult(UUID customerId, String email, boolean verified) {}
}
