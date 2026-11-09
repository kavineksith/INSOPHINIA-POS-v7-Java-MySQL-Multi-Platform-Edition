package com.insophinia.pos.service;

import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillItem;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.entity.Item;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BillPdfService} — the A4 receipt HTML generator.
 *
 * Coverage:
 *   - Section toggles honored (sub-items, specs, warranty, revisions, T&C)
 *   - T&C content sanitized via Jsoup (script tags stripped)
 *   - Sub-items render as child rows under their parent
 *   - Specifications pretty-printed from JSON
 *   - Warranty row appears only when warrantyMonths > 0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BillPdfServiceTest {

    @Mock private SettingsService settings;

    private BillPdfService service;

    @BeforeEach
    void setUp() {
        service = new BillPdfService(settings);
    }

    private Bill makeBill(BillItem... items) {
        Bill bill = new Bill();
        bill.setId(UUID.randomUUID());
        bill.setBillNumber("BILL-20261019-0001");
        bill.setCustomerName("Alice");
        bill.setStaffName("Cashier One");
        bill.setSubtotal(BigDecimal.valueOf(1000));
        bill.setTotalDiscount(BigDecimal.ZERO);
        bill.setVatAmount(BigDecimal.valueOf(180));
        bill.setSsclAmount(BigDecimal.valueOf(25));
        bill.setTaxTotal(BigDecimal.valueOf(205));
        bill.setTotalAmount(BigDecimal.valueOf(1205));
        bill.setPaidAmount(BigDecimal.valueOf(1500));
        bill.setBalance(BigDecimal.valueOf(295));
        bill.setCreatedAt(Instant.now());

        for (BillItem bi : items) {
            bi.setBill(bill);
            bill.getBillItems().add(bi);
        }
        return bill;
    }

    private BillItem makeItem(String name, String plu, int warrantyMonths, String specs) {
        BillItem bi = new BillItem();
        bi.setId(UUID.randomUUID());
        bi.setPluCode(plu);
        bi.setItemName(name);
        bi.setQuantity(BigDecimal.ONE);
        bi.setUnit("pcs");
        bi.setUnitPrice(BigDecimal.valueOf(500));
        bi.setActualPrice(BigDecimal.valueOf(500));
        bi.setDiscount(BigDecimal.ZERO);
        bi.setDiscountPercentage(BigDecimal.ZERO);
        bi.setSubtotal(BigDecimal.valueOf(500));
        bi.setWarrantyMonths(warrantyMonths);
        bi.setSpecifications(specs);
        bi.setRevisionCount(2);
        bi.setRevisionPrice(BigDecimal.valueOf(50));
        Item item = new Item();
        item.setId(UUID.randomUUID());
        bi.setItem(item);
        return bi;
    }

    @Test
    void a4HtmlIncludesSubItemsWhenEnabled() {
        BillItem parent = makeItem("Website development", "WD-001", 6,
                "{\"Scope\":\"5 pages\",\"Tech\":\"the API\"}");
        BillItem child = makeItem("Frontend design", "WD-001-SUB", 0, null);
        child.setParentBillItem(parent);
        Bill bill = makeBill(parent, child);

        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SUBITEMS, true)).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SPECIFICATIONS, true)).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_WARRANTY, true)).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_REVISIONS, true)).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, false)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("Website development"), "Parent item name must appear");
        assertTrue(html.contains("Frontend design"), "Sub-item name must appear");
        assertTrue(html.contains("sub-item"), "Sub-item CSS class must be applied");
    }

    @Test
    void a4HtmlExcludesSubItemsWhenDisabled() {
        BillItem parent = makeItem("Website development", "WD-001", 0, null);
        BillItem child = makeItem("Frontend design", "WD-001-SUB", 0, null);
        child.setParentBillItem(parent);
        Bill bill = makeBill(parent, child);

        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SUBITEMS, true)).thenReturn(false);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SPECIFICATIONS, true)).thenReturn(false);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_WARRANTY, true)).thenReturn(false);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_REVISIONS, true)).thenReturn(false);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, false)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("Website development"), "Parent item must still appear");
        assertFalse(html.contains("Frontend design"), "Sub-items must be hidden when setting is false");
    }

    @Test
    void a4HtmlShowsWarrantyRowWhenWarrantyMonthsPositive() {
        BillItem bi = makeItem("Dell Laptop", "DL-001", 12, null);
        Bill bill = makeBill(bi);

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("Warranty:"), "Warranty row must appear when warrantyMonths > 0");
        assertTrue(html.contains("12 months"), "Warranty duration must be rendered");
    }

    @Test
    void a4HtmlOmitsWarrantyRowWhenWarrantyMonthsZero() {
        BillItem bi = makeItem("Coca-Cola", "CC-001", 0, null);
        Bill bill = makeBill(bi);

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertFalse(html.contains("Warranty:"),
                "Warranty row must NOT appear when warrantyMonths = 0 (e.g. a soft drink)");
    }

    @Test
    void a4HtmlPrettyPrintsSpecificationsFromJson() {
        BillItem bi = makeItem("Laptop", "LP-001", 0,
                "{\"RAM\":\"16GB\",\"Storage\":\"512GB SSD\",\"Color\":\"Space Gray\"}");
        Bill bill = makeBill(bi);

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("RAM"), "RAM spec must appear");
        assertTrue(html.contains("16GB"), "RAM value must appear");
        assertTrue(html.contains("Storage"), "Storage spec must appear");
        assertTrue(html.contains("512GB SSD"), "Storage value must appear");
        assertTrue(html.contains("Color"), "Color spec must appear");
        assertTrue(html.contains("Space Gray"), "Color value must appear");
    }

    @Test
    void a4HtmlSanitizesTermsAndConditionsContent() {
        Bill bill = makeBill(makeItem("Item", "IT-001", 0, null));

        String maliciousTc = "<h2>Terms</h2><script>alert('xss')</script>"
                + "<iframe src='evil.com'></iframe><p>Safe content</p>";

        // Generic stubs first (so specific stubs override them)
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");
        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        // Specific stubs last (override the generic ones for specific keys)
        when(settings.getOrDefault(SettingsService.KEY_A4_BILL_TERMS_CONTENT, ""))
                .thenReturn(maliciousTc);

        String html = service.buildA4Html(bill);

        // Verify the T&C page was included
        assertTrue(html.contains("terms"), "T&C section must appear when include_terms is true");
        // Verify the malicious content was stripped by Jsoup
        assertFalse(html.contains("<script>"), "Script tags must be stripped from T&C content");
        assertFalse(html.contains("evil.com"), "Iframe src must be stripped");
        assertTrue(html.contains("Safe content"), "Safe T&C content must survive sanitization");
        assertTrue(html.contains("Terms"), "T&C heading must appear");
    }

    @Test
    void a4HtmlIncludesRevisionsRowForFreelanceItems() {
        BillItem bi = makeItem("Logo design", "LD-001", 0, null);
        bi.setRevisionCount(3);
        bi.setRevisionPrice(BigDecimal.valueOf(25));
        Bill bill = makeBill(bi);

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(false);
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("Revisions included:"), "Revisions row must appear when revisionCount > 0");
        assertTrue(html.contains("3"), "Revision count must appear");
        assertTrue(html.contains("additional revisions"), "Revision price text must appear");
    }

    @Test
    void a4HtmlHasPageBreakForTermsAndConditions() {
        Bill bill = makeBill(makeItem("Item", "IT-001", 0, null));

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(true);
        when(settings.getOrDefault(SettingsService.KEY_A4_BILL_TERMS_CONTENT, ""))
                .thenReturn("<h2>Terms</h2><p>Content</p>");
        when(settings.getOrDefault(anyString(), anyString())).thenReturn("INSOPHINIA POS");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("page-break-before: always"),
                "T&C section must start on a new A4 page (CSS page-break-before)");
    }

    @Test
    void a4HtmlShowsShopHeaderFromSettings() {
        Bill bill = makeBill(makeItem("Item", "IT-001", 0, null));

        when(settings.getBool(anyString(), anyBoolean())).thenReturn(true);
        when(settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true)).thenReturn(false);
        when(settings.getOrDefault(SettingsService.KEY_SHOP_NAME, "INSOPHINIA POS"))
                .thenReturn("My Custom Shop");
        when(settings.getOrDefault(SettingsService.KEY_SHOP_ADDRESS, ""))
                .thenReturn("123 Main Street, Colombo");
        when(settings.getOrDefault(SettingsService.KEY_SHOP_PHONE, ""))
                .thenReturn("+94 77 123 4567");

        String html = service.buildA4Html(bill);

        assertTrue(html.contains("My Custom Shop"), "Shop name from settings must appear");
        assertTrue(html.contains("123 Main Street"), "Shop address must appear");
        assertTrue(html.contains("+94 77 123 4567"), "Shop phone must appear");
    }
}
