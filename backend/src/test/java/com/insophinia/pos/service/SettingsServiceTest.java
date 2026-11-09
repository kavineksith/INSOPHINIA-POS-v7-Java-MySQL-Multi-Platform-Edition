package com.insophinia.pos.service;

import com.insophinia.pos.entity.Setting;
import com.insophinia.pos.repository.SettingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SettingsServiceTest {
    @Mock SettingRepository repository;
    @InjectMocks SettingsService service;

    @Test
    void missingSettingUsesProvidedDefault() {
        when(repository.findByKey("tax_enabled")).thenReturn(Optional.empty());
        assertFalse(service.getBool("tax_enabled", false));
    }

    @Test
    void booleanSettingAcceptsTrueCaseInsensitively() {
        Setting setting = new Setting();
        setting.setKey("email_enabled");
        setting.setValue("TRUE");
        when(repository.findByKey("email_enabled")).thenReturn(Optional.of(setting));
        assertTrue(service.getBool("email_enabled", false));
    }

    @Test
    void malformedIntegerFallsBackToDefault() {
        Setting setting = new Setting();
        setting.setKey("items_per_page");
        setting.setValue("not-a-number");
        when(repository.findByKey("items_per_page")).thenReturn(Optional.of(setting));
        assertEquals(25, service.getInt("items_per_page", 25));
    }
}
