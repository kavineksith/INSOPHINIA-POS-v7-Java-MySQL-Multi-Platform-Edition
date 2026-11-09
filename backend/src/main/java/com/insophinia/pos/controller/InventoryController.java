package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.InventoryDtos.LowStockItem;
import com.insophinia.pos.dto.InventoryDtos.StockAdjustmentRequest;
import com.insophinia.pos.dto.InventoryDtos.StockMovementResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.service.InventoryService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryService service;

    public InventoryController(InventoryService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('view:inventory')")
    public PagedResponse<StockMovementResponse> movements(@RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "50") int limit,
                                                          @RequestParam(required = false) UUID itemId) {
        return PagedResponse.of(service.listMovements(itemId,
                PageRequest.of(page - 1, limit, Sort.by("movementDate").descending()))
                .map(InventoryService::toResponse));
    }

    @GetMapping("/low-stock")
    @PreAuthorize("hasAuthority('view:inventory')")
    public ApiResponse<List<LowStockItem>> lowStock() {
        return ApiResponse.ok(service.lowStock());
    }

    @PostMapping("/stock-in")
    @PreAuthorize("hasAuthority('manage:inventory')")
    public ApiResponse<StockMovementResponse> stockIn(@Valid @RequestBody StockAdjustmentRequest req) {
        return ApiResponse.ok(service.stockIn(req, SecurityUtils.requireUser().userId()), "Stock added");
    }

    @PostMapping("/stock-out")
    @PreAuthorize("hasAuthority('manage:inventory')")
    public ApiResponse<StockMovementResponse> stockOut(@Valid @RequestBody StockAdjustmentRequest req) {
        return ApiResponse.ok(service.stockOut(req, SecurityUtils.requireUser().userId()), "Stock removed");
    }

    @PostMapping("/damage")
    @PreAuthorize("hasAuthority('manage:inventory')")
    public ApiResponse<StockMovementResponse> damage(@Valid @RequestBody StockAdjustmentRequest req) {
        return ApiResponse.ok(service.damage(req, SecurityUtils.requireUser().userId()), "Damage recorded");
    }

    @PostMapping("/lost")
    @PreAuthorize("hasAuthority('manage:inventory')")
    public ApiResponse<StockMovementResponse> lost(@Valid @RequestBody StockAdjustmentRequest req) {
        return ApiResponse.ok(service.lost(req, SecurityUtils.requireUser().userId()), "Loss recorded");
    }
}
