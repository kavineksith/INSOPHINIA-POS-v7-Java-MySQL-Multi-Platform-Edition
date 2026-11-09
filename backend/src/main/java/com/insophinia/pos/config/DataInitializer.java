package com.insophinia.pos.config;

import com.insophinia.pos.entity.Category;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.entity.Item;
import com.insophinia.pos.entity.Promotion;
import com.insophinia.pos.entity.SettingType;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.entity.UserRole;
import com.insophinia.pos.repository.CategoryRepository;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.PromotionRepository;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.service.SettingsService;
import com.insophinia.pos.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Seeds the database on first boot:
 *
 * <ul>
 *   <li><b>Always</b>: master_admin account (if none exists) and canonical
 *       settings keys (loyalty point value, VAT/SSCL tax rates, shop info,
 *       email-enabled flag). Production-safe.</li>
 *   <li><b>Opt-in</b> via {@code app.seed.demo-data=true} (defaults to false):
 *       one demo staff user per non-master role, a few categories, a small
 *       item catalog with stock, two promotions, and a few customers. Useful
 *       for local development and Cypress E2E tests. Never enabled in
 *       production.</li>
 * </ul>
 *
 * Idempotent: each block checks for existing rows before inserting, so the
 * seed can be re-run safely. Demo data uses fixed PLU codes / phone numbers
 * so the seed is deterministic and Cypress fixtures can target them.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final CategoryRepository categories;
    private final ItemRepository items;
    private final CustomerRepository customers;
    private final PromotionRepository promotions;
    private final PasswordEncoder passwordEncoder;
    private final SettingsService settings;
    private final String adminUsername;
    private final String adminPassword;
    private final boolean seedDemoData;

    public DataInitializer(UserRepository users,
                           CategoryRepository categories,
                           ItemRepository items,
                           CustomerRepository customers,
                           PromotionRepository promotions,
                           PasswordEncoder passwordEncoder,
                           SettingsService settings,
                           @Value("${app.seed.admin-username:master}") String adminUsername,
                           @Value("${app.seed.admin-password}") String adminPassword,
                           @Value("${app.seed.demo-data:false}") boolean seedDemoData) {
        this.users = users;
        this.categories = categories;
        this.items = items;
        this.customers = customers;
        this.promotions = promotions;
        this.passwordEncoder = passwordEncoder;
        this.settings = settings;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.seedDemoData = seedDemoData;
    }

    @Override
    public void run(String... args) {
        seedMasterAdmin();
        seedDefaultSettings();
        if (seedDemoData) {
            seedDemoCatalog();
        }
    }

    private void seedMasterAdmin() {
        if (users.countByRoleAndDeletedAtIsNull(UserRole.master_admin) == 0) {
            UserService.validatePassword(adminPassword, adminUsername);
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setEmail("admin@insophinia.local");
            admin.setFirstName("Master");
            admin.setLastName("Admin");
            admin.setRole(UserRole.master_admin);
            admin.setPassword(passwordEncoder.encode(adminPassword));
            admin.setMustChangePassword(true);
            admin.setPasswordChangedAt(Instant.now());
            admin.setPasswordExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
            users.save(admin);
            log.info("Seeded master_admin account '{}' — change the password on first login", adminUsername);
        }
    }

    private void seedDefaultSettings() {
        seedSetting(SettingsService.KEY_LOYALTY_POINT_VALUE, "100", SettingType.integer);
        seedSetting(SettingsService.KEY_TAX_ENABLED, "true", SettingType.bool);
        seedSetting(SettingsService.KEY_VAT_RATE, "18", SettingType.string);
        seedSetting(SettingsService.KEY_SSCL_RATE, "2.5", SettingType.string);
        seedSetting(SettingsService.KEY_EMAIL_ENABLED, "false", SettingType.bool);
        seedSetting(SettingsService.KEY_SHOP_NAME, "INSOPHINIA", SettingType.string);
        seedSetting(SettingsService.KEY_SHOP_ADDRESS, "123 Main St, City", SettingType.string);
        seedSetting(SettingsService.KEY_SHOP_PHONE, "+94 123 456 789", SettingType.string);
    }

    /**
     * Seed demo catalog data for development & E2E tests. All demo rows use
     * deterministic PLU / phone / email prefixes ("DEMO-…") so they can be
     * identified and cleaned up if needed. The seed is idempotent — if the
     * demo PLU already exists, no rows are inserted.
     */
    private void seedDemoCatalog() {
        // Demo staff (cashier + supervisor + admin) — only created if missing.
        // Password is the same as the seed master admin password (configurable
        // via app.seed.admin-password). All demo users are marked
        // mustChangePassword=false so Cypress can log in directly.
        if (users.findByUsernameAndDeletedAtIsNull("cashier1").isEmpty()) {
            seedDemoUser("cashier1", "cashier1@insophinia.local", "Cashier", "One", UserRole.cashier);
        }
        if (users.findByUsernameAndDeletedAtIsNull("supervisor1").isEmpty()) {
            seedDemoUser("supervisor1", "supervisor1@insophinia.local", "Super", "Visor", UserRole.supervisor);
        }
        if (users.findByUsernameAndDeletedAtIsNull("admin1").isEmpty()) {
            seedDemoUser("admin1", "admin1@insophinia.local", "Admin", "One", UserRole.admin);
        }

        // Demo categories
        Category beverages = categories.findByNameAndDeletedAtIsNull("Beverages")
                .orElseGet(() -> saveCategory("Beverages", "Soft drinks, juices, water"));
        Category snacks = categories.findByNameAndDeletedAtIsNull("Snacks")
                .orElseGet(() -> saveCategory("Snacks", "Crisps, biscuits, chocolates"));
        Category dairy = categories.findByNameAndDeletedAtIsNull("Dairy")
                .orElseGet(() -> saveCategory("Dairy", "Milk, yoghurt, cheese"));
        Category bakery = categories.findByNameAndDeletedAtIsNull("Bakery")
                .orElseGet(() -> saveCategory("Bakery", "Bread, rolls, pastries"));

        // Demo items (PLU prefixes with DEMO- so they're easy to filter out)
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0001").isEmpty()) {
            saveItem("DEMO-0001", "Coca-Cola 500ml", beverages, "5001", "1001",
                    new BigDecimal("120.00"), new BigDecimal("85.00"), new BigDecimal("24"));
        }
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0002").isEmpty()) {
            saveItem("DEMO-0002", "Sprite 500ml", beverages, "5002", "1002",
                    new BigDecimal("120.00"), new BigDecimal("85.00"), new BigDecimal("18"));
        }
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0003").isEmpty()) {
            saveItem("DEMO-0003", "Plain Salt Crisps 50g", snacks, "5003", "1003",
                    new BigDecimal("80.00"), new BigDecimal("55.00"), new BigDecimal("30"));
        }
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0004").isEmpty()) {
            saveItem("DEMO-0004", "Chocolate Bar 45g", snacks, "5004", "1004",
                    new BigDecimal("150.00"), new BigDecimal("100.00"), new BigDecimal("12"));
        }
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0005").isEmpty()) {
            saveItem("DEMO-0005", "Fresh Milk 1L", dairy, "5005", "1005",
                    new BigDecimal("410.00"), new BigDecimal("360.00"), new BigDecimal("8"));
        }
        if (items.findByPluCodeAndDeletedAtIsNull("DEMO-0006").isEmpty()) {
            saveItem("DEMO-0006", "White Bread 400g", bakery, "5006", "1006",
                    new BigDecimal("180.00"), new BigDecimal("130.00"), new BigDecimal("5"));
        }

        // Demo promotions
        if (promotions.findByNameAndDeletedAtIsNull("Snack Friday 10% off").isEmpty()) {
            Promotion p = new Promotion();
            items.findByPluCodeAndDeletedAtIsNull("DEMO-0004").ifPresent(p::setItem);
            p.setName("Snack Friday 10% off");
            p.setDescription("10% off chocolate bars on Fridays");
            p.setDiscountType(com.insophinia.pos.entity.DiscountType.percentage);
            p.setDiscountValue(new BigDecimal("10.00"));
            p.setMinItemCount(1);
            p.setStartDate(Instant.now().minus(1, ChronoUnit.DAYS));
            p.setEndDate(Instant.now().plus(30, ChronoUnit.DAYS));
            p.setActive(true);
            promotions.save(p);
        }

        // Demo customers (unique phone numbers, all unverified email)
        if (customers.findByPhoneAndDeletedAtIsNull("+94770000001").isEmpty()) {
            saveCustomer("Demo Customer Alice", "alice@demo.local", "+94770000001", 250);
        }
        if (customers.findByPhoneAndDeletedAtIsNull("+94770000002").isEmpty()) {
            saveCustomer("Demo Customer Bob", "bob@demo.local", "+94770000002", 0);
        }

        log.info("Seeded demo data (cashier1, supervisor1, admin1, 4 categories, 6 items, 1 promotion, 2 customers).");
    }

    private void seedDemoUser(String username, String email, String firstName, String lastName, UserRole role) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setRole(role);
        u.setPassword(passwordEncoder.encode(adminPassword));
        u.setMustChangePassword(false);
        u.setPasswordChangedAt(Instant.now());
        u.setPasswordExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        users.save(u);
    }

    private Category saveCategory(String name, String description) {
        Category c = new Category();
        c.setName(name);
        c.setDescription(description);
        c.setActive(true);
        return categories.save(c);
    }

    private void saveItem(String plu, String name, Category category, String barcode, String qr,
                          BigDecimal price, BigDecimal cost, BigDecimal stock) {
        Item i = new Item();
        i.setPluCode(plu);
        i.setName(name);
        i.setCategory(category);
        i.setBarcode(barcode);
        i.setQrCode(qr);
        i.setPrice(price);
        i.setCostPrice(cost);
        i.setUnit("pcs");
        i.setStockQuantity(stock);
        i.setTotalQuantity(stock);
        i.setLowStockThreshold(new BigDecimal("5"));
        i.setActive(true);
        items.save(i);
    }

    private void saveCustomer(String name, String email, String phone, int loyaltyPoints) {
        Customer c = new Customer();
        c.setName(name);
        c.setEmail(email);
        c.setPhone(phone);
        c.setLoyaltyPoints(loyaltyPoints);
        customers.save(c);
    }

    private void seedSetting(String key, String value, SettingType type) {
        if (settings.get(key).isEmpty()) {
            settings.upsert(key, value, type);
        }
    }
}
