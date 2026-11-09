package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.CatalogDtos.CategoryRequest;
import com.insophinia.pos.dto.CatalogDtos.CategoryResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerRequest;
import com.insophinia.pos.dto.CatalogDtos.CustomerResponse;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationResult;
import com.insophinia.pos.dto.CatalogDtos.CustomerVerificationStatus;
import com.insophinia.pos.dto.CatalogDtos.ItemRequest;
import com.insophinia.pos.dto.CatalogDtos.ItemResponse;
import com.insophinia.pos.dto.CatalogDtos.PromotionRequest;
import com.insophinia.pos.dto.CatalogDtos.PromotionResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.service.CatalogService;
import com.insophinia.pos.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;

/** Holds the simple CRUD controllers for the catalog domain. */
public final class CatalogControllers {
    private CatalogControllers() {}

    @RestController
    @RequestMapping("/api/categories")
    public static class CategoryController {
        private final CatalogService service;
        public CategoryController(CatalogService service) { this.service = service; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:categories')")
        public PagedResponse<CategoryResponse> list(@RequestParam(defaultValue = "1") int page,
                                                    @RequestParam(defaultValue = "50") int limit,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(defaultValue = "name") String sort,
                                                    @RequestParam(defaultValue = "asc") String direction,
                                                    @RequestParam(defaultValue = "false") boolean includeDeleted,
                                                    @RequestParam(defaultValue = "false") boolean deletedOnly) {
            int safePage = Math.max(1, page);
            int safeLimit = Math.max(1, Math.min(limit, 100));
            String safeSort = java.util.Set.of("name", "createdAt", "updatedAt").contains(sort) ? sort : "name";
            Sort ordering = "desc".equalsIgnoreCase(direction) ? Sort.by(safeSort).descending() : Sort.by(safeSort).ascending();
            return PagedResponse.of(service.listCategories(search, includeDeleted, deletedOnly, PageRequest.of(safePage - 1, safeLimit, ordering))
                    .map(CatalogService::toCategoryResponse));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('view:categories')")
        public ApiResponse<CategoryResponse> get(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toCategoryResponse(service.getCategory(id)));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('manage:categories')")
        public ApiResponse<CategoryResponse> create(@Valid @RequestBody CategoryRequest req) {
            return ApiResponse.ok(service.createCategory(req), "Category created");
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:categories')")
        public ApiResponse<CategoryResponse> update(@PathVariable UUID id, @Valid @RequestBody CategoryRequest req) {
            return ApiResponse.ok(service.updateCategory(id, req), "Category updated");
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:categories')")
        public ApiResponse<Map<String, Object>> delete(@PathVariable UUID id,
                @RequestParam(defaultValue = "soft") String mode) {
            boolean hard = "hard".equalsIgnoreCase(mode);
            if (hard && !com.insophinia.pos.security.SecurityUtils.requireUser().role().equals(com.insophinia.pos.entity.UserRole.master_admin)) {
                throw new com.insophinia.pos.exception.ForbiddenException("Hard delete requires master_admin role");
            }
            service.deleteCategory(id, hard);
            return ApiResponse.ok(Map.<String,Object>of("deleted", true, "mode", mode), "Category " + (hard ? "permanently deleted" : "soft-deleted"));
        }

        @PostMapping("/{id}/restore")
        @PreAuthorize("hasAuthority('manage:categories')")
        public ApiResponse<CategoryResponse> restore(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toCategoryResponse(service.restoreCategory(id)), "Category restored");
        }
    }

    @RestController
    @RequestMapping("/api/items")
    public static class ItemController {
        private final CatalogService service;
        public ItemController(CatalogService service) { this.service = service; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:items')")
        public PagedResponse<ItemResponse> list(@RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String search,
                @RequestParam(defaultValue = "name") String sort, @RequestParam(defaultValue = "asc") String direction,
                @RequestParam(defaultValue = "false") boolean includeDeleted,
                @RequestParam(defaultValue = "false") boolean deletedOnly) {
            int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 100));
            String safeSort = java.util.Set.of("name", "pluCode", "price", "stockQuantity", "createdAt").contains(sort) ? sort : "name";
            Sort ordering = "desc".equalsIgnoreCase(direction) ? Sort.by(safeSort).descending() : Sort.by(safeSort).ascending();
            return PagedResponse.of(service.listItems(search, safeSort, direction, includeDeleted, deletedOnly, PageRequest.of(safePage - 1, safeLimit, ordering))
                    .map(CatalogService::toItemResponse));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('view:items')")
        public ApiResponse<ItemResponse> get(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toItemResponse(service.getItem(id)));
        }

        @GetMapping("/lookup/{code}")
        @PreAuthorize("hasAuthority('view:items')")
        public ApiResponse<ItemResponse> lookup(@PathVariable String code) {
            return ApiResponse.ok(CatalogService.toItemResponse(service.findByBarcodeOrPlu(code)));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('manage:items')")
        public ApiResponse<ItemResponse> create(@Valid @RequestBody ItemRequest req) {
            return ApiResponse.ok(service.createItem(req), "Item created");
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:items')")
        public ApiResponse<ItemResponse> update(@PathVariable UUID id, @Valid @RequestBody ItemRequest req) {
            return ApiResponse.ok(service.updateItem(id, req), "Item updated");
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:items')")
        public ApiResponse<Map<String, Object>> delete(@PathVariable UUID id,
                @RequestParam(defaultValue = "soft") String mode) {
            boolean hard = "hard".equalsIgnoreCase(mode);
            if (hard && !com.insophinia.pos.security.SecurityUtils.requireUser().role().equals(com.insophinia.pos.entity.UserRole.master_admin)) {
                throw new com.insophinia.pos.exception.ForbiddenException("Hard delete requires master_admin role");
            }
            service.deleteItem(id, hard);
            return ApiResponse.ok(Map.<String,Object>of("deleted", true, "mode", mode), "Item " + (hard ? "permanently deleted" : "soft-deleted"));
        }

        @PostMapping("/{id}/restore")
        @PreAuthorize("hasAuthority('manage:items')")
        public ApiResponse<ItemResponse> restore(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toItemResponse(service.restoreItem(id)), "Item restored");
        }

        @PostMapping("/bulk-delete")
        @PreAuthorize("hasAuthority('manage:items')")
        public ApiResponse<Map<String, Integer>> bulkDelete(@RequestBody Map<String, List<UUID>> body) {
            int count = service.bulkDelete(body.getOrDefault("ids", List.of()));
            return ApiResponse.ok(Map.of("deleted", count), "Items deleted");
        }

        @GetMapping(value = "/export", produces = "text/csv")
        @PreAuthorize("hasAuthority('export:reports')")
        public org.springframework.http.ResponseEntity<String> export() {
            StringBuilder csv = new StringBuilder("id,pluCode,name,category,barcode,qrCode,price,costPrice,stockQuantity,active\n");
            int page = 0; org.springframework.data.domain.Page<ItemResponse> batch;
            do {
                batch = service.listItems(PageRequest.of(page++, 100, Sort.by("name"))).map(CatalogService::toItemResponse);
                for (ItemResponse i : batch.getContent()) csv.append(csv(i.id())).append(',').append(csv(i.pluCode())).append(',')
                        .append(csv(i.name())).append(',').append(csv(i.categoryName())).append(',').append(csv(i.barcode())).append(',')
                        .append(csv(i.qrCode())).append(',').append(csv(i.price())).append(',').append(csv(i.costPrice())).append(',')
                        .append(csv(i.stockQuantity())).append(',').append(i.active()).append('\n');
            } while (batch.hasNext());
            return org.springframework.http.ResponseEntity.ok().header("Content-Disposition", "attachment; filename=insophinia-items.csv")
                    .contentType(new org.springframework.http.MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8)).body(csv.toString());
        }
        private static String csv(Object value) { String v = value == null ? "" : value.toString(); return "\"" + v.replace("\"", "\"\"") + "\""; }
    }

    @RestController
    @RequestMapping("/api/promotions")
    public static class PromotionController {
        private final CatalogService service;
        public PromotionController(CatalogService service) { this.service = service; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:promotions')")
        public PagedResponse<PromotionResponse> list(@RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String search,
                @RequestParam(required = false) Boolean active,
                @RequestParam(defaultValue = "false") boolean includeDeleted,
                @RequestParam(defaultValue = "false") boolean deletedOnly) {
            int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 100));
            return PagedResponse.of(service.listPromotions(search, includeDeleted, deletedOnly, PageRequest.of(safePage - 1, safeLimit))
                    .map(CatalogService::toPromotionResponse));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('view:promotions')")
        public ApiResponse<PromotionResponse> get(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toPromotionResponse(service.getPromotion(id)));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('manage:promotions')")
        public ApiResponse<PromotionResponse> create(@Valid @RequestBody PromotionRequest req) {
            return ApiResponse.ok(service.createPromotion(req), "Promotion created");
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:promotions')")
        public ApiResponse<PromotionResponse> update(@PathVariable UUID id, @Valid @RequestBody PromotionRequest req) {
            return ApiResponse.ok(service.updatePromotion(id, req), "Promotion updated");
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:promotions')")
        public ApiResponse<Map<String, Object>> delete(@PathVariable UUID id,
                @RequestParam(defaultValue = "soft") String mode) {
            boolean hard = "hard".equalsIgnoreCase(mode);
            if (hard && !com.insophinia.pos.security.SecurityUtils.requireUser().role().equals(com.insophinia.pos.entity.UserRole.master_admin)) {
                throw new com.insophinia.pos.exception.ForbiddenException("Hard delete requires master_admin role");
            }
            service.deletePromotion(id, hard);
            return ApiResponse.ok(Map.<String,Object>of("deleted", true, "mode", mode), "Promotion " + (hard ? "permanently deleted" : "soft-deleted"));
        }

        @PostMapping("/{id}/restore")
        @PreAuthorize("hasAuthority('manage:promotions')")
        public ApiResponse<PromotionResponse> restore(@PathVariable UUID id) {
            return ApiResponse.ok(CatalogService.toPromotionResponse(service.restorePromotion(id)), "Promotion restored");
        }
    }

    @RestController
    @RequestMapping("/api/customers")
    public static class CustomerController {
        private final CustomerService service;
        public CustomerController(CustomerService service) { this.service = service; }

        @GetMapping
        @PreAuthorize("hasAuthority('view:customers')")
        public PagedResponse<CustomerResponse> list(@RequestParam(defaultValue = "1") int page,
                @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String search,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                @RequestParam(defaultValue = "createdAt") String sort,
                @RequestParam(defaultValue = "desc") String direction,
                @RequestParam(defaultValue = "false") boolean includeDeleted,
                @RequestParam(defaultValue = "false") boolean deletedOnly) {
            int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 100));
            String safeSort = java.util.Set.of("name", "email", "phone", "createdAt", "loyaltyPoints").contains(sort) ? sort : "createdAt";
            Sort ordering = "asc".equalsIgnoreCase(direction) ? Sort.by(safeSort).ascending() : Sort.by(safeSort).descending();
            Instant start = from == null && to == null && (search == null || search.isBlank()) ? Instant.now().minus(2, java.time.temporal.ChronoUnit.DAYS) : from;
            if (start != null && to != null && to.isBefore(start)) throw new com.insophinia.pos.exception.BadRequestException("End date must be on or after start date");
            return PagedResponse.of(service.list(search, start, to, includeDeleted, deletedOnly, PageRequest.of(safePage - 1, safeLimit, ordering))
                    .map(CustomerService::toResponse));
        }

        @GetMapping("/{id}")
        @PreAuthorize("hasAuthority('view:customers')")
        public ApiResponse<CustomerResponse> get(@PathVariable UUID id) {
            return ApiResponse.ok(CustomerService.toResponse(service.get(id)));
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @PreAuthorize("hasAuthority('manage:customers')")
        public ApiResponse<CustomerResponse> create(@Valid @RequestBody CustomerRequest req) {
            return ApiResponse.ok(service.create(req), "Customer created");
        }

        @PutMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:customers')")
        public ApiResponse<CustomerResponse> update(@PathVariable UUID id, @Valid @RequestBody CustomerRequest req) {
            return ApiResponse.ok(service.update(id, req), "Customer updated");
        }

        @DeleteMapping("/{id}")
        @PreAuthorize("hasAuthority('manage:customers')")
        public ApiResponse<Map<String, Object>> delete(@PathVariable UUID id,
                @RequestParam(defaultValue = "soft") String mode) {
            boolean hard = "hard".equalsIgnoreCase(mode);
            if (hard && !com.insophinia.pos.security.SecurityUtils.requireUser().role().equals(com.insophinia.pos.entity.UserRole.master_admin)) {
                throw new com.insophinia.pos.exception.ForbiddenException("Hard delete requires master_admin role");
            }
            service.delete(id, hard);
            return ApiResponse.ok(Map.<String,Object>of("deleted", true, "mode", mode), "Customer " + (hard ? "permanently deleted" : "soft-deleted"));
        }

        @PostMapping("/{id}/restore")
        @PreAuthorize("hasAuthority('manage:customers')")
        public ApiResponse<CustomerResponse> restore(@PathVariable UUID id) {
            return ApiResponse.ok(CustomerService.toResponse(service.restore(id)), "Customer restored");
        }

        // -----------------------------------------------------------------
        // Email verification lifecycle (included)
        // -----------------------------------------------------------------

        /**
         * Public endpoint: consumes a one-time verification token.
         * Placed on the root path so it can be linked from an email without
         * exposing any customer ID.
         */
        @GetMapping("/verify-email")
        public ApiResponse<CustomerVerificationResult> verifyEmail(@RequestParam("token") String token) {
            return ApiResponse.ok(service.verifyEmailByToken(token), "Email verified");
        }

        /**
         * Admin/manager endpoint: regenerate + resend the verification email
         * for a customer whose email is on file but not yet verified.
         */
        @PostMapping("/{id}/resend-verification")
        @PreAuthorize("hasAuthority('manage:customers')")
        public ApiResponse<CustomerVerificationStatus> resendVerification(@PathVariable UUID id) {
            return ApiResponse.ok(service.resendVerification(id), "Verification email reissued");
        }
    }
}
