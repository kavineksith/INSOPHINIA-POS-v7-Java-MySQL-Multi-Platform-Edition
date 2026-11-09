package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.V7Dtos.RepairCreateRequest;
import com.insophinia.pos.dto.V7Dtos.RepairItemRequest;
import com.insophinia.pos.dto.V7Dtos.RepairItemResponse;
import com.insophinia.pos.dto.V7Dtos.RepairResponse;
import com.insophinia.pos.dto.V7Dtos.RepairStatusTransitionRequest;
import com.insophinia.pos.entity.Repair;
import com.insophinia.pos.entity.RepairItem;
import com.insophinia.pos.entity.RepairStatus;
import com.insophinia.pos.security.Permissions;
import com.insophinia.pos.service.RepairService;
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
import java.util.Map;
import java.util.UUID;

/**
 * Repair job endpoints — receive, transition, add line items, recompute.
 *
 * RBAC:
 *   - view:repairs (supervisor+): GET
 *   - manage:repairs (supervisor+): POST (create + transition + line items)
 */
@RestController
@RequestMapping("/api/repairs")
public class RepairController {

    private final RepairService service;

    public RepairController(RepairService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_REPAIRS + "')")
    public PagedResponse<RepairResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RepairStatus status,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID technicianId) {
        int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 200));
        PageRequest pr = PageRequest.of(safePage - 1, safeLimit, Sort.by("receivedAt").descending());
        return PagedResponse.of(service.list(search, status, customerId, technicianId, pr).map(this::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_REPAIRS + "')")
    public ApiResponse<RepairResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_REPAIRS + "')")
    public ApiResponse<RepairResponse> create(@Valid @RequestBody RepairCreateRequest req) {
        return ApiResponse.ok(toResponse(service.create(req)), "Repair job created");
    }

    @PostMapping("/{id}/transition")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_REPAIRS + "')")
    public ApiResponse<RepairResponse> transition(
            @PathVariable UUID id, @Valid @RequestBody RepairStatusTransitionRequest req) {
        return ApiResponse.ok(toResponse(service.transition(id, req)), "Status updated");
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_REPAIRS + "')")
    public ApiResponse<List<RepairItemResponse>> listItems(@PathVariable UUID id) {
        return ApiResponse.ok(service.listItems(id).stream().map(this::toItemResponse).toList());
    }

    @PostMapping("/{id}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_REPAIRS + "')")
    public ApiResponse<RepairItemResponse> addLineItem(
            @PathVariable UUID id, @Valid @RequestBody RepairItemRequest req) {
        return ApiResponse.ok(toItemResponse(service.addLineItem(id, req)), "Line item added");
    }

    @PostMapping("/{id}/recompute")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_REPAIRS + "')")
    public ApiResponse<RepairResponse> recompute(@PathVariable UUID id) {
        return ApiResponse.ok(toResponse(service.recomputeActualCost(id)), "Actual cost recomputed");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_REPAIRS + "')")
    public ApiResponse<Map<String, Boolean>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true), "Repair deleted");
    }

    // ----- helpers ------------------------------------------------------

    private RepairResponse toResponse(Repair r) {
        List<RepairItemResponse> items = service.listItems(r.getId()).stream()
                .map(this::toItemResponse).toList();
        return new RepairResponse(r.getId(), r.getRepairNumber(),
                r.getCustomer() == null ? null : r.getCustomer().getId(),
                r.getCustomerName(), r.getCustomerEmail(), r.getCustomerPhone(),
                r.getItemDescription(), r.getItemSerial(), r.getProblemReported(),
                r.getDiagnosis(),
                r.getTechnician() == null ? null : r.getTechnician().getId(),
                r.getTechnicianName(), r.getStatus().name(), r.getPriority().name(),
                r.getReceivedAt(), r.getDiagnosedAt(), r.getStartedAt(),
                r.getCompletedAt(), r.getDeliveredAt(), r.getClosedAt(),
                r.getEstimatedCost(), r.getActualCost(),
                r.getFinalBill() == null ? null : r.getFinalBill().getId(),
                r.getWarranty() == null ? null : r.getWarranty().getId(),
                r.getWarrantyMonths(), r.getNotes(), r.getCreatedAt(), r.getUpdatedAt(), items);
    }

    private RepairItemResponse toItemResponse(RepairItem ri) {
        return new RepairItemResponse(ri.getId(), ri.getLineType().name(),
                ri.getDescription(), ri.getItem() == null ? null : ri.getItem().getId(),
                ri.getQuantity(), ri.getUnitPrice(), ri.getDiscount(), ri.getSubtotal(),
                ri.getNotes(), ri.getCreatedAt());
    }
}
