package com.insophinia.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record LoginResponse(
            String token,
            boolean requires2fa,
            String pendingToken,
            UUID userId,
            String username,
            String role,
            String fullName,
            boolean mustChangePassword,
            List<String> permissions) {}

    public record TwoFactorVerifyRequest(@NotBlank String pendingToken, String code, String backupCode) {}
    public record TwoFactorSetupRequest(@NotBlank String password) {}
    public record TwoFactorSetupResponse(String secret, String otpauthUrl, List<String> backupCodes) {}
    public record TwoFactorEnableRequest(@NotBlank String code) {}
    public record TwoFactorDisableRequest(@NotBlank String password) {}

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128) String newPassword) {}

    public record SessionResponse(
            UUID id, String ipAddress, String browser, String os, String device,
            String createdAt, String lastActivityAt, String expiresAt, boolean current) {}
}
