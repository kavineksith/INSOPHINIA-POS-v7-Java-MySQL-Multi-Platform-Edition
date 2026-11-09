package com.insophinia.pos.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MiscDtos {
    private MiscDtos() {}

    public record DashboardResponse(
            BigDecimal todaySales, long todayBills, long totalItems, long lowStockCount,
            long totalCustomers, long totalLoyaltyPoints, BigDecimal monthSales,
            List<Map<String, Object>> salesLast7Days,
            List<Map<String, Object>> topItems) {}

    public record SalesReportRow(String date, long bills, BigDecimal revenue, BigDecimal discount) {}

    public record SyncEvent(long seq, String type, String entity, Instant timestamp, Map<String, Object> data) {}

    public record SyncChangesResponse(List<SyncEvent> events, long currentSeq, Instant serverTime) {}

    public record SystemStatusResponse(boolean locked, String lockReason, Instant lockedAt,
                                       Instant lastCheckinAt, int deadmanDays) {}

    public record SystemLockRequest(boolean locked, @jakarta.validation.constraints.Size(max = 500) String reason) {}

    public record SystemCheckinRequest(String deviceIdentifier) {}

    public record SecurityEventResponse(UUID id, UUID userId, String eventType, String ipAddress,
                                        String details, String severity, Instant createdAt) {}

    public record SystemLogResponse(UUID id, String level, String category, String action,
                                    String message, String metadata, UUID userId, String ipAddress,
                                    Instant createdAt) {}

    public record BackupResponse(UUID id, String filename, long fileSize, String backupType, Instant createdAt) {}
}
