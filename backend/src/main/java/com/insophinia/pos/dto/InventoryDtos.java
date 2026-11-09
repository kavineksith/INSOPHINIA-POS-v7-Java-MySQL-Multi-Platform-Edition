package com.insophinia.pos.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public final class InventoryDtos {
    private InventoryDtos() {}

    public record StockAdjustmentRequest(@NotNull UUID itemId,
                                         @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
                                         String notes) {}

    public record StockMovementResponse(UUID id, UUID itemId, String itemName, String movementType,
                                        BigDecimal quantity, String referenceType, UUID referenceId,
                                        String notes, Instant movementDate, UUID createdBy) {}

    public record LowStockItem(UUID id, String pluCode, String name, BigDecimal stockQuantity,
                               BigDecimal lowStockThreshold) {}
}
