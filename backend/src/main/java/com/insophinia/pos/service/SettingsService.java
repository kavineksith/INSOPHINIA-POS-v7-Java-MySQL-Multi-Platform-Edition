package com.insophinia.pos.service;

import com.insophinia.pos.entity.Setting;
import com.insophinia.pos.entity.SettingType;
import com.insophinia.pos.repository.SettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class SettingsService {

    // Tax rates remain separate so admin UIs can tune them independently.
    public static final String KEY_VAT_RATE = "vat_rate";
    public static final String KEY_SSCL_RATE = "sscl_rate";

    // Canonical tax enablement flag (reconciled from legacy vat_enabled +
    // sscl_enabled booleans in Flyway migration V2). VAT and SSCL are
    // intentionally toggled together by the single tax_enabled key — this is
    // the the current version production behaviour carried forward. If a future deployment
    // needs to disable VAT but keep SSCL, split into two keys here and add a
    // dedicated migration.
    public static final String KEY_TAX_ENABLED = "tax_enabled";
    public static final String KEY_VAT_ENABLED  = KEY_TAX_ENABLED; // alias for backward compatibility
    public static final String KEY_SSCL_ENABLED = KEY_TAX_ENABLED; // alias for backward compatibility
    public static final String KEY_LOYALTY_POINT_VALUE = "loyalty_point_value";
    public static final String KEY_EMAIL_ENABLED = "email_enabled";
    public static final String KEY_SHOP_NAME = "shop_name";
    public static final String KEY_SHOP_ADDRESS = "shop_address";
    public static final String KEY_SHOP_PHONE = "shop_phone";

    // v7 feature settings (added by Flyway migration V3).
    public static final String KEY_A4_BILL_INCLUDE_SUBITEMS = "a4_bill_include_subitems";
    public static final String KEY_A4_BILL_INCLUDE_SPECIFICATIONS = "a4_bill_include_specifications";
    public static final String KEY_A4_BILL_INCLUDE_WARRANTY = "a4_bill_include_warranty";
    public static final String KEY_A4_BILL_INCLUDE_REVISIONS = "a4_bill_include_revisions";
    public static final String KEY_A4_BILL_INCLUDE_TERMS = "a4_bill_include_terms";
    public static final String KEY_A4_BILL_TERMS_CONTENT = "a4_bill_terms_content";
    public static final String KEY_WHATSAPP_DEFAULT_FORMAT = "whatsapp_default_format";
    public static final String KEY_EMAIL_DEFAULT_FORMAT = "email_default_format";
    public static final String KEY_MARKETING_RATE_LIMIT_PER_HOUR = "marketing_rate_limit_per_hour";
    public static final String KEY_MARKETING_REQUIRE_DOUBLE_OPT_IN = "marketing_require_double_opt_in";

    private final SettingRepository repository;

    public SettingsService(SettingRepository repository) {
        this.repository = repository;
    }

    public List<Setting> all() { return repository.findAll(); }

    public Optional<String> get(String key) {
        return repository.findByKey(key).map(Setting::getValue);
    }

    public String getOrDefault(String key, String def) {
        return get(key).orElse(def);
    }

    public int getInt(String key, int def) {
        try { return Integer.parseInt(getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public double getDouble(String key, double def) {
        try { return Double.parseDouble(getOrDefault(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }

    public boolean getBool(String key, boolean def) {
        return get(key).map(v -> v.equalsIgnoreCase("true") || v.equals("1")).orElse(def);
    }

    @Transactional
    public void upsert(String key, String value, SettingType type) {
        Setting s = repository.findByKey(key).orElseGet(() -> {
            Setting created = new Setting();
            created.setKey(key);
            created.setType(type);
            return created;
        });
        s.setValue(value);
        repository.save(s);
    }

    @Transactional
    public void upsertAll(Map<String, String> settings) {
        settings.forEach((k, v) -> upsert(k, v, SettingType.string));
    }
}
