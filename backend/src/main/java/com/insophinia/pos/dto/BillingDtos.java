package com.insophinia.pos.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {
    private BillingDtos() {}

    public record BillItemRequest(@NotNull UUID itemId,
                                  @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity) {}

    public record CreateBillRequest(
            UUID customerId,
            String customerName,
            String customerEmail,
            @NotEmpty @Valid List<BillItemRequest> items,
            @NotNull @DecimalMin("0") BigDecimal paidAmount,
            Integer pointsRedeemed,
            String authorizerUsername,
            String authorizerPassword,
            String remarks) {}

    public record BillItemResponse(UUID id, UUID itemId, String pluCode, String itemName,
                                   BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal actualPrice,
                                   BigDecimal discount, BigDecimal discountPercentage, BigDecimal subtotal) {}

    public record BillResponse(UUID id, String billNumber, UUID customerId, String customerName,
                               String customerEmail, String staffName,
                               Instant startTime, Instant endTime,
                               BigDecimal subtotal, BigDecimal totalDiscount,
                               BigDecimal vatAmount, BigDecimal ssclAmount, BigDecimal taxTotal,
                               BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal balance,
                               String status, String returnReason, String remarks,
                               boolean printed, boolean emailSent,
                               int earnedPoints, int pointsRedeemed, BigDecimal pointsValue,
                               String pointsAuthorizedBy, int totalPointsAfter,
                               Instant createdAt, List<BillItemResponse> items) {}

    public record ReturnBillRequest(@NotBlank String reason,
                                    @NotBlank String authorizerUsername,
                                    @NotBlank String authorizerPassword) {}

    public record CancelBillRequest(@Size(max = 500) String reason, @Size(max = 60) String authorizerUsername, @Size(max = 128) String authorizerPassword) {}

    public record EmailReceiptRequest(@Email @Size(max = 254) String email) {}
}
