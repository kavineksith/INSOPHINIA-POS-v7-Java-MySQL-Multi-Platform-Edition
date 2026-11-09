package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.V7Dtos.MarketingCampaignCreateRequest;
import com.insophinia.pos.dto.V7Dtos.MarketingCampaignResponse;
import com.insophinia.pos.dto.V7Dtos.MarketingSendLogResponse;
import com.insophinia.pos.entity.MarketingCampaign;
import com.insophinia.pos.entity.MarketingCampaignStatus;
import com.insophinia.pos.entity.MarketingSendLog;
import com.insophinia.pos.security.Permissions;
import com.insophinia.pos.service.MarketingService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Marketing campaign endpoints — admin+ only.
 *
 * Permission matrix:
 *   - view:marketing (admin+): GET
 *   - manage:marketing (admin+): POST/DELETE
 *
 * The send endpoint forces the campaign to be picked up by the scheduler
 * within 60s. Manual send-now is also supported via the create payload.
 */
@RestController
@RequestMapping("/api/marketing")
public class MarketingController {

    private final MarketingService service;

    public MarketingController(MarketingService service) { this.service = service; }

    @GetMapping("/campaigns")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_MARKETING + "')")
    public PagedResponse<MarketingCampaignResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) MarketingCampaignStatus status) {
        int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 200));
        PageRequest pr = PageRequest.of(safePage - 1, safeLimit, Sort.by("createdAt").descending());
        return PagedResponse.of(service.list(status, pr).map(this::toResponse));
    }

    @GetMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_MARKETING + "')")
    public ApiResponse<MarketingCampaignResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.get(id)));
    }

    @PostMapping("/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_MARKETING + "')")
    public ApiResponse<MarketingCampaignResponse> create(@Valid @RequestBody MarketingCampaignCreateRequest req) {
        return ApiResponse.ok(toResponse(service.create(req)), "Campaign created");
    }

    @PostMapping("/campaigns/{id}/send")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_MARKETING + "')")
    public ApiResponse<MarketingCampaignResponse> sendNow(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.sendCampaign(id)), "Campaign send loop executed");
    }

    @PostMapping("/campaigns/{id}/cancel")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_MARKETING + "')")
    public ApiResponse<MarketingCampaignResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.cancel(id)), "Campaign cancelled");
    }

    @DeleteMapping("/campaigns/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_MARKETING + "')")
    public ApiResponse<java.util.Map<String, Boolean>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(java.util.Map.of("deleted", true), "Campaign deleted");
    }

    @GetMapping("/campaigns/{id}/sends")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_MARKETING + "')")
    public PagedResponse<MarketingSendLogResponse> listSendLogs(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit) {
        int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 500));
        PageRequest pr = PageRequest.of(safePage - 1, safeLimit, Sort.by("createdAt").ascending());
        return PagedResponse.of(service.listSendLogs(id, pr).map(this::toSendLogResponse));
    }

    // ----- helpers ------------------------------------------------------

    private MarketingCampaignResponse toResponse(MarketingCampaign c) {
        return new MarketingCampaignResponse(c.getId(), c.getName(), c.getDescription(),
                c.getTemplate() == null ? null : c.getTemplate().getId(),
                c.getTemplate() == null ? null : c.getTemplate().getName(),
                c.getStatus() == null ? null : c.getStatus().name(),
                c.getTargetFilter(), c.getScheduledAt(), c.getStartedAt(), c.getCompletedAt(),
                c.getSnapshotSubject(), c.getTargetCount(), c.getSentCount(), c.getFailedCount(),
                c.getOpenedCount(), c.getClickedCount(),
                c.getCreatedBy() == null ? null : c.getCreatedBy().getId(),
                c.getCreatedAt());
    }

    private MarketingSendLogResponse toSendLogResponse(MarketingSendLog l) {
        return new MarketingSendLogResponse(l.getId(), l.getCampaign() == null ? null : l.getCampaign().getId(),
                l.getCustomer() == null ? null : l.getCustomer().getId(),
                l.getCustomerEmail(), l.getStatus() == null ? null : l.getStatus().name(),
                l.getFailureReason(), l.getSentAt(), l.getOpenedAt(), l.getClickedAt(), l.getCreatedAt());
    }
}
