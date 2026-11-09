package com.insophinia.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public final class SettingDtos {
    private SettingDtos() {}

    public record SettingResponse(UUID id, String key, String value, String type, String description) {}

    public record UpdateSettingsRequest(@NotNull Map<String, String> settings) {}

    public record DeviceProfileRequest(@NotBlank String deviceIdentifier, String name,
                                       String defaultPrinter, String defaultScanner, Boolean active) {}

    public record DeviceProfileResponse(UUID id, String deviceIdentifier, String name,
                                        String defaultPrinter, String defaultScanner, boolean active) {}

    public record TestEmailRequest(@NotBlank @jakarta.validation.constraints.Email @jakarta.validation.constraints.Size(max = 254) String to) {}
}
