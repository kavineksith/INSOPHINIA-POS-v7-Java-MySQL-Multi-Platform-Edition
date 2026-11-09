package com.insophinia.pos.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v7 feature DTOs — warranty, repair, email template, marketing, and
 * enhanced billing (sub-items + multi-channel send + A4 PDF).
 *
 * Layout mirrors {@link BillingDtos}: records are immutable + use Jakarta
 * Bean Validation constraints so Spring's @Valid surface rejects malformed
 * payloads before they reach the service layer.
 */
public final class V7Dtos {
    private V7Dtos() {}

    // =========================================================================
    // EmailTemplate
    // =========================================================================

    public record EmailTemplateRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank String kind,             // receipt|marketing|test|welcome|custom
            @NotBlank @Size(max = 255) String subject,
            @NotBlank @Size(max = 65535) String bodyHtml,
            @Size(max = 65535) String bodyText,
            @Size(max = 500) String allowedVariables,
            Boolean active,
            Boolean isDefault) {}

    public record EmailTemplateResponse(
            UUID id, String name, String kind, String subject,
            String bodyHtml, String bodyText, String allowedVariables,
            boolean active, boolean isDefault, Instant createdAt, Instant updatedAt) {}

    public record EmailTemplateRenderRequest(
            @NotNull UUID templateId,
            @NotNull Map<String, String> variables) {}

    public record EmailTemplateRenderResponse(
            String subject, String bodyHtml, String bodyText) {}

    // =========================================================================
    // Warranty + WarrantyClaim
    // =========================================================================

    /** Manually file a new warranty (e.g. for items sold before v7). */
    public record WarrantyCreateRequest(
            UUID billId,
            UUID billItemId,
            UUID customerId,
            @NotBlank String customerName,
            @Email String customerEmail,
            @Size(max = 20) String customerPhone,
            UUID itemId,
            @NotBlank String itemName,
            @Size(max = 50) String pluCode,
            @Size(max = 100) String serialNumber,
            @NotNull @Min(0) @Max(60) Integer warrantyMonths,
            String notes) {}

    public record WarrantyResponse(
            UUID id, UUID billId, UUID billItemId, UUID customerId, String customerName,
            String customerEmail, String customerPhone, UUID itemId, String itemName,
            String pluCode, String serialNumber, int warrantyMonths,
            Instant warrantyStartsAt, Instant warrantyExpiresAt,
            String status, String notes, int openClaimsCount,
            Instant createdAt, Instant updatedAt) {}

    public record WarrantyClaimFileRequest(
            @NotBlank String faultReported,
            String notes) {}

    public record WarrantyClaimResolveRequest(
            @NotBlank String resolution,           // repair|replacement|refund|rejected|escalated
            String resolutionNotes,
            UUID replacementItemId,
            @DecimalMin("0") BigDecimal refundAmount,
            String diagnosis) {}

    public record WarrantyClaimUpdateStatusRequest(
            @NotBlank String status,              // open|in_progress|resolved|rejected|closed
            String technicianNotes) {}

    public record WarrantyClaimResponse(
            UUID id, UUID warrantyId, UUID customerId, String customerName,
            String claimNumber, String faultReported,
            UUID technicianId, String technicianName, String diagnosis,
            String resolution, String resolutionNotes,
            UUID replacementItemId, BigDecimal refundAmount, String status,
            Instant filedAt, Instant resolvedAt, Instant createdAt) {}

    // =========================================================================
    // Repair + RepairItem
    // =========================================================================

    public record RepairItemRequest(
            @NotBlank String lineType,             // labour|part|fee|tax|other
            @NotBlank @Size(max = 500) String description,
            UUID itemId,
            @DecimalMin("0") BigDecimal quantity,
            @DecimalMin("0") BigDecimal unitPrice,
            @DecimalMin("0") BigDecimal discount,
            String notes) {}

    public record RepairCreateRequest(
            UUID customerId,
            @NotBlank String customerName,
            @Email String customerEmail,
            @Size(max = 20) String customerPhone,
            @NotBlank @Size(max = 500) String itemDescription,
            @Size(max = 100) String itemSerial,
            @NotBlank String problemReported,
            String priority,                       // low|normal|high|urgent
            UUID technicianId,
            @DecimalMin("0") BigDecimal estimatedCost,
            List<RepairItemRequest> items,
            String notes) {}

    public record RepairStatusTransitionRequest(
            @NotBlank String nextStatus,           // received|diagnosed|in_progress|awaiting_parts|completed|delivered|closed|rejected|cancelled
            String diagnosis,
            @DecimalMin("0") BigDecimal actualCost,
            UUID finalBillId,
            @Min(0) @Max(60) Integer warrantyMonths) {}

    public record RepairItemResponse(
            UUID id, String lineType, String description, UUID itemId,
            BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount,
            BigDecimal subtotal, String notes, Instant createdAt) {}

    public record RepairResponse(
            UUID id, String repairNumber, UUID customerId, String customerName,
            String customerEmail, String customerPhone, String itemDescription,
            String itemSerial, String problemReported, String diagnosis,
            UUID technicianId, String technicianName, String status, String priority,
            Instant receivedAt, Instant diagnosedAt, Instant startedAt,
            Instant completedAt, Instant deliveredAt, Instant closedAt,
            BigDecimal estimatedCost, BigDecimal actualCost,
            UUID finalBillId, UUID warrantyId, int warrantyMonths,
            String notes, Instant createdAt, Instant updatedAt,
            List<RepairItemResponse> items) {}

    // =========================================================================
    // Marketing Campaign
    // =========================================================================

    public record MarketingCampaignCreateRequest(
            @NotBlank @Size(max = 255) String name,
            @Size(max = 65535) String description,
            @NotNull UUID templateId,
            String targetFilter,                  // JSON document
            Instant scheduledAt,
            Boolean sendNow) {}

    public record MarketingTargetFilter(
            @Min(0) Integer loyaltyPointsMin,
            @Min(0) Integer loyaltyPointsMax,
            Instant createdAfter,
            Instant createdBefore,
            Boolean hasEmail,
            Boolean hasPhone,
            Boolean emailVerified,
            List<UUID> customerIds) {}

    public record MarketingCampaignResponse(
            UUID id, String name, String description, UUID templateId,
            String templateName, String status, String targetFilter,
            Instant scheduledAt, Instant startedAt, Instant completedAt,
            String snapshotSubject, int targetCount, int sentCount,
            int failedCount, int openedCount, int clickedCount,
            UUID createdBy, Instant createdAt) {}

    public record MarketingSendLogResponse(
            UUID id, UUID campaignId, UUID customerId, String customerEmail,
            String status, String failureReason, Instant sentAt,
            Instant openedAt, Instant clickedAt, Instant createdAt) {}

    // =========================================================================
    // Enhanced billing — sub-items + multi-channel send
    // =========================================================================

    public record SubItemRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String specifications,                // JSON document for variant specs
            @Min(0) @Max(60) Integer warrantyMonths,
            @Min(0) Integer revisionCount,
            @DecimalMin("0") BigDecimal revisionPrice,
            @Min(0) Integer sortOrder) {}

    public record BillItemWithSubsRequest(
            @NotNull UUID itemId,
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            String specifications,
            @Min(0) @Max(60) Integer warrantyMonths,
            @Min(0) Integer revisionCount,
            @DecimalMin("0") BigDecimal revisionPrice,
            @Min(0) Integer sortOrder,
            @Valid @Size(max = 50) List<SubItemRequest> subItems) {}

    public record CreateBillV7Request(
            UUID customerId,
            String customerName,
            String customerEmail,
            @NotEmpty @Valid List<BillItemWithSubsRequest> items,
            @NotNull @DecimalMin("0") BigDecimal paidAmount,
            Integer pointsRedeemed,
            String authorizerUsername,
            String authorizerPassword,
            String remarks) {}

    public record SubItemResponse(
            UUID id, UUID itemId, String pluCode, String itemName, BigDecimal quantity,
            String unit, BigDecimal unitPrice, BigDecimal actualPrice,
            BigDecimal discount, BigDecimal discountPercentage, BigDecimal subtotal,
            String specifications, int warrantyMonths, Instant warrantyExpiresAt,
            int revisionCount, BigDecimal revisionPrice, int sortOrder) {}

    public record BillItemV7Response(
            UUID id, UUID itemId, String pluCode, String itemName, BigDecimal quantity,
            String unit, BigDecimal unitPrice, BigDecimal actualPrice,
            BigDecimal discount, BigDecimal discountPercentage, BigDecimal subtotal,
            String specifications, int warrantyMonths, Instant warrantyExpiresAt,
            int revisionCount, BigDecimal revisionPrice, int sortOrder,
            List<SubItemResponse> subItems) {}

    public record BillV7Response(
            UUID id, String billNumber, UUID customerId, String customerName,
            String customerEmail, String staffName,
            BigDecimal subtotal, BigDecimal totalDiscount,
            BigDecimal vatAmount, BigDecimal ssclAmount, BigDecimal taxTotal,
            BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal balance,
            String status, String remarks, Instant createdAt,
            List<BillItemV7Response> items) {}

    // =========================================================================
    // Multi-channel send
    // =========================================================================

    public record BillSendRequest(
            @NotBlank String channel,             // whatsapp|email
            @NotBlank String format,              // text|pdf
            @NotBlank @Email String recipient) {}   // phone (E.164) or email

    public record BillSendResponse(
            UUID logId, String channel, String format, String recipient,
            String status, Instant sentAt, String failureReason) {}

    public record BillSendLogResponse(
            UUID id, UUID billId, String channel, String format, String recipient,
            String status, String failureReason, UUID sentBy,
            Instant sentAt, Instant createdAt) {}
}
