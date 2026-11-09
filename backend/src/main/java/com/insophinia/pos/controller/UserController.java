package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.UserDtos.CreateUserRequest;
import com.insophinia.pos.dto.UserDtos.ResetPasswordRequest;
import com.insophinia.pos.dto.UserDtos.UpdateUserRequest;
import com.insophinia.pos.dto.UserDtos.UserResponse;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.service.UserService;
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

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('view:users')")
    public PagedResponse<UserResponse> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt") String sort, @RequestParam(defaultValue = "desc") String direction,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(defaultValue = "false") boolean deletedOnly) {
        int safePage = Math.max(1, page), safeLimit = Math.max(1, Math.min(limit, 100));
        String safeSort = java.util.Set.of("username", "email", "firstName", "lastName", "createdAt", "role").contains(sort) ? sort : "createdAt";
        Sort ordering = "asc".equalsIgnoreCase(direction) ? Sort.by(safeSort).ascending() : Sort.by(safeSort).descending();
        return PagedResponse.of(service.list(search, includeDeleted, deletedOnly, PageRequest.of(safePage - 1, safeLimit, ordering)).map(UserService::toResponse));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('view:users')")
    public ApiResponse<UserResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(UserService.toResponse(service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('manage:users')")
    public ApiResponse<UserResponse> create(@Valid @RequestBody CreateUserRequest req) {
        return ApiResponse.ok(service.create(req), "User created");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('manage:users')")
    public ApiResponse<UserResponse> update(@PathVariable UUID id, @Valid @RequestBody UpdateUserRequest req) {
        return ApiResponse.ok(service.update(id, req), "User updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('manage:users')")
    public ApiResponse<Map<String, Object>> delete(@PathVariable UUID id,
            @RequestParam(defaultValue = "soft") String mode) {
        boolean hard = "hard".equalsIgnoreCase(mode);
        if (hard && !SecurityUtils.requireUser().role().equals(com.insophinia.pos.entity.UserRole.master_admin)) {
            throw new com.insophinia.pos.exception.ForbiddenException("Hard delete requires master_admin role");
        }
        service.delete(id, SecurityUtils.requireUser().userId(), hard);
        return ApiResponse.ok(Map.<String,Object>of("deleted", true, "mode", mode), "User " + (hard ? "permanently deleted" : "soft-deleted"));
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('manage:users')")
    public ApiResponse<UserResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(UserService.toResponse(service.restore(id)), "User restored");
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('manage:users')")
    public ApiResponse<Map<String, Boolean>> resetPassword(@PathVariable UUID id,
                                                           @Valid @RequestBody ResetPasswordRequest req) {
        service.resetPassword(id, req.newPassword());
        return ApiResponse.ok(Map.of("reset", true), "Password reset; user must change it at next login");
    }
}
