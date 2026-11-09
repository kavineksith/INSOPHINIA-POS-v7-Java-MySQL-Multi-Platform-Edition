package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.MiscDtos.DashboardResponse;
import com.insophinia.pos.dto.CustomerDisplayDtos.CartSnapshot;
import jakarta.validation.Valid;
import com.insophinia.pos.dto.MiscDtos.SalesReportRow;
import com.insophinia.pos.dto.MiscDtos.SyncChangesResponse;
import com.insophinia.pos.dto.MiscDtos.SyncEvent;
import com.insophinia.pos.dto.MiscDtos.SystemCheckinRequest;
import com.insophinia.pos.dto.MiscDtos.SystemLockRequest;
import com.insophinia.pos.dto.MiscDtos.SystemStatusResponse;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.service.ReportService;
import com.insophinia.pos.service.SyncHub;
import com.insophinia.pos.service.SystemService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/** Dashboard, reports, sync and system controllers. */
public final class MiscControllers {
    private MiscControllers() {}

    @RestController
    @RequestMapping("/api/dashboard")
    public static class DashboardController {
        private final ReportService reports;
        public DashboardController(ReportService reports) { this.reports = reports; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:dashboard')")
        public ApiResponse<DashboardResponse> dashboard() {
            return ApiResponse.ok(reports.dashboard());
        }
    }

    @RestController
    @RequestMapping("/api/reports")
    public static class ReportController {
        private final ReportService reports;
        public ReportController(ReportService reports) { this.reports = reports; }

        @GetMapping("/sales")
        @PreAuthorize("hasAuthority('view:reports')")
        public ApiResponse<List<SalesReportRow>> sales(
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
            Instant f = from == null ? Instant.now().minus(30, ChronoUnit.DAYS) : from;
            Instant t = to == null ? Instant.now() : to;
            List<SalesReportRow> rows = reports.salesReport(f, t).stream()
                    .map(r -> new SalesReportRow(String.valueOf(r[0]), ((Number) r[1]).longValue(),
                            (BigDecimal) r[2], (BigDecimal) r[3]))
                    .toList();
            return ApiResponse.ok(rows);
        }

        @GetMapping("/items")
        @PreAuthorize("hasAuthority('view:reports')")
        public ApiResponse<List<Map<String, Object>>> items(
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
            Instant f = from == null ? Instant.now().minus(30, ChronoUnit.DAYS) : from;
            Instant t = to == null ? Instant.now() : to;
            List<Map<String, Object>> rows = reports.itemsReport(f, t).stream()
                    .map(r -> Map.<String, Object>of(
                            "itemId", r.getItemId(), "pluCode", r.getPluCode(), "itemName", r.getItemName(),
                            "totalQty", r.getTotalQty(), "totalRevenue", r.getTotalRevenue()))
                    .toList();
            return ApiResponse.ok(rows);
        }
    }

    @RestController
    @RequestMapping("/api/sync")
    public static class SyncController {
        private final SyncHub syncHub;
        public SyncController(SyncHub syncHub) { this.syncHub = syncHub; }

        /** SSE stream for sub-second terminal sync — port of /api/sync/stream. */
        @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        public SseEmitter stream() {
            var user = SecurityUtils.requireUser();
            return syncHub.register(SyncHub.newClientId(user.userId()));
        }

        @GetMapping(path = "/customer-display/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        @PreAuthorize("hasAuthority('view:billing')")
        public SseEmitter customerDisplayStream(@org.springframework.web.bind.annotation.RequestHeader("X-Display-Channel") String channel) {
            requireDisplayChannel(channel);
            var user = SecurityUtils.requireUser();
            return syncHub.registerDisplay(SyncHub.newClientId(user.userId()), channel);
        }

        @PostMapping("/customer-display/cart")
        @PreAuthorize("hasAuthority('manage:billing')")
        public ApiResponse<Map<String, Boolean>> publishCustomerCart(@Valid @RequestBody CartSnapshot snapshot) {
            requireDisplayChannel(snapshot.channel());
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("terminalId", snapshot.terminalId()); data.put("items", snapshot.items());
            data.put("total", snapshot.total()); data.put("currency", snapshot.currency());
            data.put("updatedAt", Instant.now().toString());
            syncHub.broadcastDisplayCart(snapshot.channel(), data);
            return ApiResponse.ok(Map.of("published", true));
        }

        /**
         * Admin endpoint: list active customer-display channels + paired
         * client counts. Used by the Settings page's "Customer display"
         * panel to show operators which terminals are currently paired.
         *
         * Returns:
         *   - allowedChannels: channels configured in the env allowlist
         *     (POS_DISPLAY_CHANNELS or POS_DISPLAY_CHANNEL), so the admin
         *     sees what's available to pair.
         *   - activeChannels: channels with at least one currently-connected
         *     display client, with their live client count.
         *   - totalDisplayClients: aggregate count of all connected display
         *     clients across all channels.
         *
         * Permission: manage:settings (admin and master_admin only).
         */
        @GetMapping("/customer-display/channels")
        @PreAuthorize("hasAuthority('manage:settings')")
        public ApiResponse<Map<String, Object>> listDisplayChannels() {
            String configured = System.getenv().getOrDefault("POS_DISPLAY_CHANNELS", "");
            if (configured.isBlank()) configured = System.getenv().getOrDefault("POS_DISPLAY_CHANNEL", "");
            java.util.List<String> allowed = new java.util.ArrayList<>();
            for (String c : configured.split(",")) {
                String v = c.trim();
                if (!v.isBlank() && v.length() >= 16) allowed.add(v);
            }
            Map<String, Integer> active = syncHub.displayChannelSnapshot();
            int totalClients = active.values().stream().mapToInt(Integer::intValue).sum();

            return ApiResponse.ok(Map.of(
                    "allowedChannels", allowed,
                    "activeChannels", active,
                    "totalDisplayClients", totalClients,
                    "generalSyncClients", syncHub.generalClientCount(),
                    "serverTime", Instant.now().toString()
            ));
        }

        private void requireDisplayChannel(String channel) {
            String configured = System.getenv().getOrDefault("POS_DISPLAY_CHANNELS", "");
            if (configured.isBlank()) configured = System.getenv().getOrDefault("POS_DISPLAY_CHANNEL", "");
            byte[] supplied = (channel == null ? "" : channel).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            boolean matched = false;
            for (String candidate : configured.split(",")) {
                String value = candidate.trim();
                if (value.length() >= 16) matched |= java.security.MessageDigest.isEqual(
                        value.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied);
            }
            if (channel == null || channel.length() < 16 || !matched) {
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "Customer display is not paired with this server");
            }
        }

        /** Delta catch-up after a disconnect — port of /api/sync/changes. */
        @GetMapping("/changes")
        public ApiResponse<SyncChangesResponse> changes(
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since,
                @RequestParam(required = false) Long lastSeq) {
            List<SyncEvent> events = syncHub.eventsSince(lastSeq, since);
            return ApiResponse.ok(new SyncChangesResponse(events, syncHub.currentSeq(), Instant.now()));
        }
    }

    @RestController
    @RequestMapping("/api/system")
    public static class SystemController {
        private final SystemService system;
        public SystemController(SystemService system) { this.system = system; }

        @GetMapping("/health")
        public ApiResponse<Map<String, Object>> health() {
            return ApiResponse.ok(Map.of("status", "ok", "time", Instant.now().toString()));
        }

        @GetMapping("/status")
        public ApiResponse<SystemStatusResponse> status() {
            return ApiResponse.ok(system.status());
        }

        @PostMapping("/lock")
        @PreAuthorize("hasAuthority('manage:system_lock')")
        public ApiResponse<SystemStatusResponse> lock(@Valid @RequestBody SystemLockRequest req) {
            return ApiResponse.ok(system.setLocked(req.locked(), req.reason(),
                    SecurityUtils.requireUser().userId()), req.locked() ? "System locked" : "System unlocked");
        }

        @PostMapping("/checkin")
        @PreAuthorize("hasAuthority('manage:system_lock')")
        public ApiResponse<Map<String, Boolean>> checkin(@RequestBody(required = false) SystemCheckinRequest req) {
            system.checkin();
            return ApiResponse.ok(Map.of("checkedIn", true));
        }
    }
}
