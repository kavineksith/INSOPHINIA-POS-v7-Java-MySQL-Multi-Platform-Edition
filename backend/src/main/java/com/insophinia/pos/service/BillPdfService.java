package com.insophinia.pos.service;

import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillItem;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates A4 receipt HTML for PDF rendering.
 *
 * Sections (toggleable via settings):
 *   - Shop header (always shown)
 *   - Bill meta (number, date, cashier)
 *   - Customer block (always shown)
 *   - Items table — top-level rows, each optionally expandable to show:
 *     - Specifications (JSON pretty-printed as key: value rows)
 *     - Sub-items (child BillItems with parentBillItem = this)
 *     - Warranty duration + expiry date
 *     - Revision count + price-per-additional-revision
 *   - Totals (subtotal / discount / VAT / SSCL / total / paid / balance)
 *   - Loyalty points line
 *   - Terms & Conditions (HTML from settings; appended on a new page)
 *
 * The generated HTML is intended to be rendered via the desktop client's
 * PDFBox PDF generator OR the web client's print-to-PDF browser feature.
 * The HTML is itself Jsoup-sanitized on output so the T&C content (which
 * comes from a settings row that an admin could edit) can't inject scripts.
 */
@Service
public class BillPdfService {

    private final SettingsService settings;

    public BillPdfService(SettingsService settings) {
        this.settings = settings;
    }

    /**
     * Build the A4 receipt HTML for the given bill. Thermal receipts call
     * a different code path (the desktop's HardwareService.buildReceiptBytes)
     * — this method is A4-only.
     */
    public String buildA4Html(Bill bill) {
        boolean includeSubitems = settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SUBITEMS, true);
        boolean includeSpecs = settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_SPECIFICATIONS, true);
        boolean includeWarranty = settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_WARRANTY, true);
        boolean includeRevisions = settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_REVISIONS, true);
        boolean includeTerms = settings.getBool(SettingsService.KEY_A4_BILL_INCLUDE_TERMS, true);

        String shopName = settings.getOrDefault(SettingsService.KEY_SHOP_NAME, "INSOPHINIA POS");
        String shopAddress = settings.getOrDefault(SettingsService.KEY_SHOP_ADDRESS, "");
        String shopPhone = settings.getOrDefault(SettingsService.KEY_SHOP_PHONE, "");

        // Group items by parent — top-level items have parentBillItem = null.
        Map<BillItem, List<BillItem>> byParent = bill.getBillItems().stream()
                .filter(bi -> bi.getParentBillItem() == null)
                .collect(Collectors.toMap(
                        parent -> parent,
                        parent -> bill.getBillItems().stream()
                                .filter(child -> parent.equals(child.getParentBillItem()))
                                .toList(),
                        (a, b) -> a,
                        java.util.LinkedHashMap::new));

        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\"><title>")
          .append(escape(bill.getBillNumber())).append("</title><style>");
        // Inline CSS so the rendered PDF is self-contained.
        sb.append("""
            @page { size: A4; margin: 16mm 14mm; }
            body { font-family: 'Helvetica Neue', Arial, sans-serif; font-size: 11pt; color: #1a1a1a; line-height: 1.5; }
            h1.shop-name { font-size: 22pt; margin: 0 0 4px; font-weight: 700; letter-spacing: 0.5px; }
            .shop-meta { font-size: 9.5pt; color: #555; margin-bottom: 16px; }
            .bill-meta { display: flex; justify-content: space-between; border-bottom: 2px solid #1a1a1a; padding-bottom: 8px; margin-bottom: 12px; font-size: 10pt; }
            .bill-meta b { font-size: 11pt; }
            table { width: 100%; border-collapse: collapse; margin-top: 8px; }
            th { background: #f3f4f6; text-align: left; padding: 6px 8px; font-size: 9.5pt; text-transform: uppercase; letter-spacing: 0.5px; border-bottom: 1px solid #d1d5db; }
            td { padding: 6px 8px; border-bottom: 1px solid #e5e7eb; vertical-align: top; font-size: 10pt; }
            .sub-item td { padding-left: 24px; font-size: 9.5pt; color: #4b5563; border-bottom: 1px dashed #e5e7eb; }
            .specs { font-size: 9pt; color: #6b7280; padding-left: 24px; padding-top: 2px; padding-bottom: 2px; }
            .specs-row { display: inline-block; margin-right: 12px; }
            .warranty-row, .revision-row { font-size: 9pt; color: #6b7280; padding-left: 24px; padding-bottom: 4px; }
            .warranty-row b, .revision-row b { color: #1f2937; }
            .totals { margin-top: 16px; margin-left: auto; width: 50%; font-size: 10pt; }
            .totals .row { display: flex; justify-content: space-between; padding: 3px 0; }
            .totals .grand { font-weight: 700; font-size: 12pt; border-top: 2px solid #1a1a1a; padding-top: 6px; margin-top: 4px; }
            .terms { page-break-before: always; margin-top: 24px; padding-top: 16px; }
            .terms h2 { font-size: 14pt; border-bottom: 1px solid #d1d5db; padding-bottom: 4px; }
            .terms li { margin-bottom: 6px; font-size: 9.5pt; color: #374151; }
            .footer { margin-top: 32px; text-align: center; font-size: 9pt; color: #6b7280; border-top: 1px solid #e5e7eb; padding-top: 8px; }
            """);
        sb.append("</style></head><body>");

        // Shop header
        sb.append("<h1 class=\"shop-name\">").append(escape(shopName)).append("</h1>");
        if (!shopAddress.isBlank()) sb.append("<div class=\"shop-meta\">").append(escape(shopAddress)).append("</div>");
        if (!shopPhone.isBlank()) sb.append("<div class=\"shop-meta\">Tel: ").append(escape(shopPhone)).append("</div>");

        // Bill meta
        sb.append("<div class=\"bill-meta\"><div>")
          .append("<b>Bill:</b> ").append(escape(bill.getBillNumber())).append("<br>")
          .append("<b>Date:</b> ").append(formatDate(bill.getCreatedAt())).append("<br>")
          .append("<b>Cashier:</b> ").append(escape(bill.getStaffName() == null ? "" : bill.getStaffName()))
          .append("</div><div style=\"text-align: right;\">")
          .append("<b>Customer:</b> ").append(escape(bill.getCustomerName() == null ? "Walk-in" : bill.getCustomerName())).append("<br>");
        if (bill.getCustomerEmail() != null && !bill.getCustomerEmail().isBlank()) {
            sb.append("<b>Email:</b> ").append(escape(bill.getCustomerEmail())).append("<br>");
        }
        sb.append("</div></div>");

        // Items table
        sb.append("<table><thead><tr><th style=\"width:50%\">Item</th><th style=\"text-align:right\">Qty</th><th style=\"text-align:right\">Unit Price</th><th style=\"text-align:right\">Subtotal</th></tr></thead><tbody>");

        for (Map.Entry<BillItem, List<BillItem>> entry : byParent.entrySet()) {
            BillItem item = entry.getKey();
            sb.append("<tr>")
              .append("<td><b>").append(escape(item.getItemName())).append("</b>");
            if (item.getPluCode() != null) sb.append("<br><small style=\"color:#6b7280\">PLU: ").append(escape(item.getPluCode())).append("</small>");
            sb.append("</td>")
              .append("<td style=\"text-align:right\">").append(item.getQuantity()).append("</td>")
              .append("<td style=\"text-align:right\">").append(money(item.getActualPrice())).append("</td>")
              .append("<td style=\"text-align:right\"><b>").append(money(item.getSubtotal())).append("</b></td>")
              .append("</tr>");

            // Specifications row
            if (includeSpecs && item.getSpecifications() != null && !item.getSpecifications().isBlank()) {
                sb.append("<tr class=\"specs\"><td colspan=\"4\">");
                sb.append(prettifySpecifications(item.getSpecifications()));
                sb.append("</td></tr>");
            }

            // Warranty row
            if (includeWarranty && item.getWarrantyMonths() > 0) {
                sb.append("<tr class=\"warranty-row\"><td colspan=\"4\"><b>Warranty:</b> ")
                  .append(item.getWarrantyMonths()).append(" months");
                if (item.getWarrantyExpiresAt() != null) {
                    sb.append(" (expires ").append(formatDate(item.getWarrantyExpiresAt())).append(")");
                }
                sb.append("</td></tr>");
            }

            // Revisions row
            if (includeRevisions && item.getRevisionCount() > 0) {
                sb.append("<tr class=\"revision-row\"><td colspan=\"4\"><b>Revisions included:</b> ")
                  .append(item.getRevisionCount());
                if (item.getRevisionPrice() != null && item.getRevisionPrice().compareTo(BigDecimal.ZERO) > 0) {
                    sb.append(" · additional revisions: ").append(money(item.getRevisionPrice())).append(" each");
                }
                sb.append("</td></tr>");
            }

            // Sub-items
            if (includeSubitems) {
                for (BillItem sub : entry.getValue()) {
                    sb.append("<tr class=\"sub-item\">")
                      .append("<td>↳ ").append(escape(sub.getItemName())).append("</td>")
                      .append("<td style=\"text-align:right\">").append(sub.getQuantity()).append("</td>")
                      .append("<td style=\"text-align:right\">").append(money(sub.getActualPrice())).append("</td>")
                      .append("<td style=\"text-align:right\">").append(money(sub.getSubtotal())).append("</td>")
                      .append("</tr>");
                    if (includeSpecs && sub.getSpecifications() != null && !sub.getSpecifications().isBlank()) {
                        sb.append("<tr class=\"specs\"><td colspan=\"4\">")
                          .append(prettifySpecifications(sub.getSpecifications()))
                          .append("</td></tr>");
                    }
                    if (includeWarranty && sub.getWarrantyMonths() > 0) {
                        sb.append("<tr class=\"warranty-row\"><td colspan=\"4\" style=\"padding-left:48px\"><b>Warranty:</b> ")
                          .append(sub.getWarrantyMonths()).append(" months</td></tr>");
                    }
                }
            }
        }
        sb.append("</tbody></table>");

        // Totals
        sb.append("<div class=\"totals\">")
          .append(row("Subtotal", money(bill.getSubtotal())))
          .append(row("Discount", "-" + money(bill.getTotalDiscount())));
        if (bill.getVatAmount() != null && bill.getVatAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(row("VAT (18%)", money(bill.getVatAmount())));
        }
        if (bill.getSsclAmount() != null && bill.getSsclAmount().compareTo(BigDecimal.ZERO) > 0) {
            sb.append(row("SSCL (2.5%)", money(bill.getSsclAmount())));
        }
        sb.append("<div class=\"row grand\"><span>Total Due</span><span>").append(money(bill.getTotalAmount())).append("</span></div>")
          .append(row("Paid", money(bill.getPaidAmount())))
          .append(row("Balance", money(bill.getBalance())));
        if (bill.getEarnedPoints() > 0 || bill.getPointsRedeemed() > 0) {
            sb.append(row("Loyalty", "+" + bill.getEarnedPoints() + " earned, -" + bill.getPointsRedeemed() + " redeemed"));
        }
        sb.append("</div>");

        // Terms & Conditions (appended on a new page)
        if (includeTerms) {
            String terms = settings.getOrDefault(SettingsService.KEY_A4_BILL_TERMS_CONTENT, "");
            if (!terms.isBlank()) {
                // Sanitize the T&C HTML via Jsoup so a malicious admin can't
                // inject script tags via the settings UI.
                String safeTerms = Jsoup.clean(terms, Safelist.relaxed());
                sb.append("<div class=\"terms\">").append(safeTerms).append("</div>");
            }
        }

        // Footer
        sb.append("<div class=\"footer\">This receipt was generated by INSOPHINIA POS · ")
          .append(formatDate(Instant.now()))
          .append("</div>");

        sb.append("</body></html>");
        return sb.toString();
    }

    /** Pretty-print a JSON specifications string as inline key: value rows. */
    private String prettifySpecifications(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> specs = mapper.readValue(json, Map.class);
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> e : specs.entrySet()) {
                sb.append("<span class=\"specs-row\"><b>").append(escape(e.getKey()))
                  .append(":</b> ").append(escape(String.valueOf(e.getValue()))).append("</span>");
            }
            return sb.toString();
        } catch (Exception e) {
            // Not valid JSON — fall back to escaped text
            return "<span class=\"specs-row\">" + escape(json) + "</span>";
        }
    }

    private String row(String label, String value) {
        return "<div class=\"row\"><span>" + escape(label) + "</span><span>" + escape(value) + "</span></div>";
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String money(BigDecimal v) {
        if (v == null) return "Rs. 0.00";
        return "Rs. " + v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatDate(Instant instant) {
        if (instant == null) return "—";
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(instant);
    }
}
