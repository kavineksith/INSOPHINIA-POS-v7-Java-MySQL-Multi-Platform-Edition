package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.print.PrinterJob;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Receipt export and native desktop print dialog. */
public final class ReceiptService {
    private ReceiptService() {}
    public static Path savePdf(JsonNode bill, Path target) throws IOException {
        List<String> lines = receiptLines(bill);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(226, Math.max(360, 90 + lines.size() * 15)));
            doc.addPage(page);
            try (PDPageContentStream out = new PDPageContentStream(doc, page)) {
                float y = page.getMediaBox().getHeight() - 28;
                for (int i = 0; i < lines.size(); i++) {
                    out.beginText(); out.setFont(new PDType1Font(i == 0 ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA), i == 0 ? 11 : 8);
                    out.newLineAtOffset(12, y); out.showText(safe(lines.get(i))); out.endText(); y -= i == 0 ? 20 : 14;
                }
            }
            doc.save(target.toFile());
        }
        return target;
    }
    public static void printNative(JsonNode bill) {
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) throw new IllegalStateException("No system printer is available. Install a printer or use Save PDF.");
        if (!job.showPrintDialog(null)) return;
        VBox receipt = new VBox(4);
        for (String line : receiptLines(bill)) { Label label = new Label(line); label.setStyle("-fx-font-family: monospace; -fx-font-size: 10px;"); receipt.getChildren().add(label); }
        if (job.printPage(receipt)) job.endJob(); else { job.cancelJob(); throw new IllegalStateException("Printer did not accept the receipt."); }
    }
    public static List<String> receiptLines(JsonNode b) {
        List<String> out = new ArrayList<>();
        out.add("INSOPHINIA POS"); out.add("--------------------------------");
        out.add("Bill: " + val(b,"billNumber")); out.add("Date: " + val(b,"createdAt")); out.add("Cashier: " + val(b,"staffName"));
        out.add("Customer: " + val(b,"customerName")); out.add("--------------------------------");
        JsonNode items = b.path("items");
        if (items.isArray()) for (JsonNode i : items) {
            out.add(cut(val(i,"itemName"), 30)); out.add("  " + val(i,"quantity") + " x " + val(i,"unitPrice") + " = " + val(i,"subtotal"));
        }
        out.add("--------------------------------"); out.add("Subtotal: " + val(b,"subtotal")); out.add("Discount: " + val(b,"totalDiscount"));
        out.add("VAT: " + val(b,"vatAmount")); out.add("SSCL: " + val(b,"ssclAmount")); out.add("Tax: " + val(b,"taxTotal"));
        out.add("TOTAL LKR: " + val(b,"totalAmount")); out.add("Paid: " + val(b,"paidAmount")); out.add("Balance: " + val(b,"balance"));
        out.add("Status: " + val(b,"status")); out.add("Thank you. Come again!"); return out;
    }
    private static String val(JsonNode n, String k) { JsonNode v=n.path(k); return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? "-" : v.asText(); }
    private static String cut(String s, int n) { return s.length() <= n ? s : s.substring(0,n); }
    private static String safe(String s) { return s.replaceAll("[^\\x20-\\x7E]", "?"); }
}
