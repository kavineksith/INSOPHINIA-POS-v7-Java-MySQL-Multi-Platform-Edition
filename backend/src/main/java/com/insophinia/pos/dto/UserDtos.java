package com.insophinia.pos.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public final class UserDtos {
    private UserDtos() {}

    public record CreateUserRequest(
            @NotBlank @Size(min = 3, max = 60) String username,
            @NotBlank @Email String email,
            @NotBlank @Size(max = 80) String firstName,
            @NotBlank @Size(max = 80) String lastName,
            @NotNull String role,
            @NotBlank @Size(min = 12, max = 128) String temporaryPassword) {}

    public record UpdateUserRequest(@Email String email, @Size(max = 80) String firstName, @Size(max = 80) String lastName, String role, Boolean active) {}

    public record ResetPasswordRequest(@NotBlank @Size(min = 12, max = 128) String newPassword) {}

    public record UserResponse(UUID id, String username, String email, String role,
                               String firstName, String lastName, boolean active,
                               boolean twoFactorEnabled, boolean locked,
                               boolean mustChangePassword, Instant createdAt) {}
}
