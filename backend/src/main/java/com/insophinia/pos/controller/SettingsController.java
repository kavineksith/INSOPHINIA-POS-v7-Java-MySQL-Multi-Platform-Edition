package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.SettingDtos.DeviceProfileRequest;
import com.insophinia.pos.dto.SettingDtos.DeviceProfileResponse;
import com.insophinia.pos.dto.SettingDtos.SettingResponse;
import com.insophinia.pos.dto.SettingDtos.TestEmailRequest;
import com.insophinia.pos.dto.SettingDtos.UpdateSettingsRequest;
import com.insophinia.pos.entity.DeviceProfile;
import com.insophinia.pos.entity.SettingType;
import com.insophinia.pos.repository.DeviceProfileRepository;
import com.insophinia.pos.service.EmailService;
import com.insophinia.pos.service.SettingsService;
import com.insophinia.pos.service.SyncHub;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final SettingsService settings;
    private final DeviceProfileRepository devices;
    private final EmailService emailService;
    private final SyncHub syncHub;

    public SettingsController(SettingsService settings, DeviceProfileRepository devices,
                              EmailService emailService, SyncHub syncHub) {
        this.settings = settings;
        this.devices = devices;
        this.emailService = emailService;
        this.syncHub = syncHub;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('view:settings')")
    public ApiResponse<List<SettingResponse>> all() {
        return ApiResponse.ok(settings.all().stream()
                .map(s -> new SettingResponse(s.getId(), s.getKey(), s.getValue(), s.getType() == com.insophinia.pos.entity.SettingType.bool ? "boolean" : s.getType().name(), s.getDescription()))
                .toList());
    }

    @PutMapping
    @PreAuthorize("hasAuthority('manage:settings')")
    public ApiResponse<Map<String, Boolean>> update(@Valid @RequestBody UpdateSettingsRequest req) {
        settings.upsertAll(req.settings());
        syncHub.broadcast("SETTINGS_UPDATED", "settings", Map.of("keys", String.join(",", req.settings().keySet())));
        return ApiResponse.ok(Map.of("updated", true), "Settings updated");
    }

    @PostMapping("/test-email")
    @PreAuthorize("hasAuthority('manage:settings')")
    public ApiResponse<Map<String, Boolean>> testEmail(@Valid @RequestBody TestEmailRequest req) {
        emailService.send(req.to(), "INSOPHINIA POS — test email",
                "<p>Your SMTP configuration is working.</p>");
        return ApiResponse.ok(Map.of("sent", true));
    }

    @GetMapping("/device")
    public ApiResponse<List<DeviceProfileResponse>> listDevices() {
        return ApiResponse.ok(devices.findAll().stream().map(SettingsController::toDeviceResponse).toList());
    }

    @PutMapping("/device")
    @PreAuthorize("hasAuthority('manage:settings')")
    public ApiResponse<DeviceProfileResponse> upsertDevice(@Valid @RequestBody DeviceProfileRequest req) {
        DeviceProfile d = devices.findByDeviceIdentifier(req.deviceIdentifier()).orElseGet(DeviceProfile::new);
        d.setDeviceIdentifier(req.deviceIdentifier());
        if (req.name() != null) d.setName(req.name());
        if (req.defaultPrinter() != null) d.setDefaultPrinter(req.defaultPrinter());
        if (req.defaultScanner() != null) d.setDefaultScanner(req.defaultScanner());
        if (req.active() != null) d.setActive(req.active());
        devices.save(d);
        syncHub.broadcast("DEVICE_REGISTERED", "settings", Map.of("device", req.deviceIdentifier()));
        return ApiResponse.ok(toDeviceResponse(d), "Device profile saved");
    }

    private static DeviceProfileResponse toDeviceResponse(DeviceProfile d) {
        return new DeviceProfileResponse(d.getId(), d.getDeviceIdentifier(), d.getName(),
                d.getDefaultPrinter(), d.getDefaultScanner(), d.isActive());
    }
}
