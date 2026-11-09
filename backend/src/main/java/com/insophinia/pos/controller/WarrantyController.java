package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimFileRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimResolveRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimResponse;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimUpdateStatusRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyCreateRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyResponse;
import com.insophinia.pos.entity.Warranty;
import com.insophinia.pos.entity.WarrantyClaim;
import com.insophinia.pos.entity.WarrantyStatus;
import com.insophinia.pos.security.Permissions;
import com.insophinia.pos.service.WarrantyService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Warranty + WarrantyClaim endpoints.
 *
 * RBAC:
 *   - view:warranties (cashier+): GET endpoints
 *   - manage:warranties (supervisor+): POST/PUT (file claim, resolve, void)
 */
@RestController
@RequestMapping("/api/warranties")
public class WarrantyController {

    private final WarrantyService service;
    private final com.insophinia.pos.repository.WarrantyClaimRepository claimRepo;

    public WarrantyController(WarrantyService service,
                              com.insophinia.pos.repository.WarrantyClaimRepository claimRepo) {
        this.service = service;
        this.claimRepo = claimRepo;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_WARRANTIES + "')")
    public PagedResponse<WarrantyResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) WarrantyStatus status,
            @RequestParam(required = false) UUID customerId) {
        int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 200));
        PageRequest pr = PageRequest.of(safePage - 1, safeLimit, Sort.by("warrantyExpiresAt").ascending());
        return PagedResponse.of(service.list(search, status, customerId, pr).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_WARRANTIES + "')")
    public ApiResponse<WarrantyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_WARRANTIES + "')")
    public ApiResponse<WarrantyResponse> create(@Valid @RequestBody WarrantyCreateRequest req) {
        return ApiResponse.ok(toResponse(service.createManual(req)), "Manual warranty created");
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_WARRANTIES + "')")
    public ApiResponse<Map<String, Boolean>> voidWarranty(
            @PathVariable UUID id, @RequestBody Map<String, String> body) {
        service.voidWarranty(id, body.getOrDefault("reason", "voided by admin"));
        return ApiResponse.ok(Map.of("voided", true), "Warranty voided");
    }

    // ----- Claims -------------------------------------------------------

    @GetMapping("/{id}/claims")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_WARRANTIES + "')")
    public ApiResponse<List<WarrantyClaimResponse>> listClaims(@PathVariable UUID id) {
        return ApiResponse.ok(service.listClaims(id).stream().map(this::toClaimResponse).toList());
    }

    @PostMapping("/{id}/claims")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_WARRANTIES + "')")
    public ApiResponse<WarrantyClaimResponse> fileClaim(
            @PathVariable UUID id, @Valid @RequestBody WarrantyClaimFileRequest req) {
        return ApiResponse.ok(toClaimResponse(service.fileClaim(id, req)), "Claim filed");
    }

    @PutMapping("/claims/{claimId}/status")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_WARRANTIES + "')")
    public ApiResponse<WarrantyClaimResponse> updateClaimStatus(
            @PathVariable UUID claimId, @Valid @RequestBody WarrantyClaimUpdateStatusRequest req) {
        return ApiResponse.ok(toClaimResponse(service.updateClaimStatus(claimId, req)), "Claim status updated");
    }

    @PostMapping("/claims/{claimId}/resolve")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_WARRANTIES + "')")
    public ApiResponse<WarrantyClaimResponse> resolveClaim(
            @PathVariable UUID claimId, @Valid @RequestBody WarrantyClaimResolveRequest req) {
        return ApiResponse.ok(toClaimResponse(service.resolveClaim(claimId, req)), "Claim resolved");
    }

    // ----- helpers ------------------------------------------------------

    private WarrantyResponse toResponse(Warranty w) {
        int openClaims = WarrantyService.openClaimsCount(w.getId(), claimRepo);
        return new WarrantyResponse(w.getId(),
                w.getBill() == null ? null : w.getBill().getId(),
                w.getBillItem() == null ? null : w.getBillItem().getId(),
                w.getCustomer() == null ? null : w.getCustomer().getId(),
                w.getCustomerName(), w.getCustomerEmail(), w.getCustomerPhone(),
                w.getItem() == null ? null : w.getItem().getId(),
                w.getItemName(), w.getPluCode(), w.getSerialNumber(),
                w.getWarrantyMonths(), w.getWarrantyStartsAt(), w.getWarrantyExpiresAt(),
                w.getStatus().name(), w.getNotes(), openClaims,
                w.getCreatedAt(), w.getUpdatedAt());
    }

    private WarrantyClaimResponse toClaimResponse(WarrantyClaim c) {
        return new WarrantyClaimResponse(c.getId(),
                c.getWarranty() == null ? null : c.getWarranty().getId(),
                c.getCustomer() == null ? null : c.getCustomer().getId(),
                c.getCustomerName(), c.getClaimNumber(), c.getFaultReported(),
                c.getTechnician() == null ? null : c.getTechnician().getId(),
                c.getTechnicianName(), c.getDiagnosis(),
                c.getResolution() == null ? null : c.getResolution().name(),
                c.getResolutionNotes(),
                c.getReplacementItem() == null ? null : c.getReplacementItem().getId(),
                c.getRefundAmount(), c.getStatus() == null ? null : c.getStatus().name(),
                c.getFiledAt(), c.getResolvedAt(), c.getCreatedAt());
    }
}
