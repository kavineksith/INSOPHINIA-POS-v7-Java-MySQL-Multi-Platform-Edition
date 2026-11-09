package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import javax.print.Doc;
import javax.print.DocFlavor;
import javax.print.DocPrintJob;
import javax.print.PrintService;
import javax.print.PrintServiceLookup;
import javax.print.SimpleDoc;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** ESC/POS raw printing and cash-drawer pulse for compatible thermal printers. */
public final class HardwareService {
    private static final byte ESC = 0x1B;
    private static final byte GS = 0x1D;
    private HardwareService() {}

    public static List<String> printers() {
        return Arrays.stream(PrintServiceLookup.lookupPrintServices(DocFlavor.BYTE_ARRAY.AUTOSENSE, null))
                .map(PrintService::getName).toList();
    }

    public static void printReceipt(JsonNode bill) throws Exception {
        String printerName = System.getenv().getOrDefault("POS_PRINTER_NAME", "");
        PrintService printer = findPrinter(printerName);
        send(printer, buildReceiptBytes(bill));
    }

    /**
     * Build the raw ESC/POS byte sequence for the given bill, WITHOUT sending
     * it to any printer. Exposed as a public method so unit tests can assert
     * on the byte sequence against the Epson TM-T20 / Star TSP100 reference
     * command sets without needing a physical printer attached.
     *
     * Byte layout:
     *   [0]      ESC (0x1B)
     *   [1]      '@' (0x40) — initialize printer (cancel all preceding commands)
     *   [2..N-5] receipt body in CP437 encoding (German/Sinhala-friendly fallback)
     *   [N-5]    GS  (0x1D)
     *   [N-4]    'V' (0x56)
     *   [N-3]    66  (0x42) — full cut
     *   [N-2]    0   (0x00) — feed-then-cut
     *   [N-1]    '\n' (0x0A) — final newline so the paper can be torn cleanly
     */
    public static byte[] buildReceiptBytes(JsonNode bill) {
        StringBuilder text = new StringBuilder();
        text.append("\n\n              INSOPHINIA POS\n");
        text.append("------------------------------------------\n");
        text.append("Bill: ").append(value(bill, "billNumber")).append('\n');
        text.append("Date: ").append(value(bill, "createdAt")).append('\n');
        text.append("Cashier: ").append(value(bill, "staffName")).append('\n');
        text.append("------------------------------------------\n");
        JsonNode lines = bill.path("items");
        if (lines.isArray()) for (JsonNode line : lines) {
            text.append(trim(value(line, "itemName"), 26)).append('\n');
            text.append("  ").append(value(line, "quantity")).append(" x ").append(value(line, "unitPrice"))
                    .append(" = ").append(value(line, "subtotal")).append('\n');
        }
        text.append("------------------------------------------\n");
        text.append("Subtotal: ").append(value(bill, "subtotal")).append('\n');
        text.append("Discount: ").append(value(bill, "totalDiscount")).append('\n');
        text.append("Tax:      ").append(value(bill, "taxTotal")).append('\n');
        text.append("TOTAL:    LKR ").append(value(bill, "totalAmount")).append('\n');
        text.append("Paid:     LKR ").append(value(bill, "paidAmount")).append('\n');
        text.append("Balance:  LKR ").append(value(bill, "balance")).append('\n');
        text.append("\n        Thank you. Come again!\n\n\n");
        byte[] body = text.toString().getBytes(Charset.forName("CP437"));
        byte[] data = new byte[2 + body.length + 5];
        data[0] = ESC; data[1] = '@'; System.arraycopy(body, 0, data, 2, body.length);
        data[data.length - 5] = GS; data[data.length - 4] = 'V'; data[data.length - 3] = 66; data[data.length - 2] = 0; data[data.length - 1] = '\n';
        return data;
    }

    /**
     * Build the cash-drawer kick pulse — ESC p m t1 t2 — without sending it.
     * Tested against Epson TM-T20 (pulse 25×250ms) and Star TSP100 (same
     * pulse duration, real-2 pin) reference command sets.
     */
    public static byte[] buildCashDrawerKickBytes() {
        return new byte[]{ESC, 0x70, 0x00, 0x19, (byte) 0xFA};
    }

    /**
     * Build the test-print byte sequence (used by the Hardware view's "Test
     * print" button). Exposed for unit-testing the same way as the receipt.
     */
    public static byte[] buildTestPrintBytes() {
        return new byte[]{ESC, '@', 'I','N','S','O','P','H','I','N','I','A',' ','P','O','S',' ','T','E','S','T','\n','\n','\n', GS, 'V', 66, 0};
    }

    public static void kickCashDrawer() throws Exception {
        PrintService printer = findPrinter(System.getenv().getOrDefault("POS_PRINTER_NAME", ""));
        send(printer, buildCashDrawerKickBytes());
    }

    public static void testPrint() throws Exception {
        PrintService printer = findPrinter(System.getenv().getOrDefault("POS_PRINTER_NAME", ""));
        send(printer, buildTestPrintBytes());
    }

    private static PrintService findPrinter(String preferred) {
        PrintService[] services = PrintServiceLookup.lookupPrintServices(DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        if (preferred != null && !preferred.isBlank()) for (PrintService service : services)
            if (service.getName().equalsIgnoreCase(preferred)) return service;
        PrintService defaultService = PrintServiceLookup.lookupDefaultPrintService();
        if (defaultService != null) return defaultService;
        if (services.length > 0) return services[0];
        throw new IllegalStateException("No compatible printer found. Set POS_PRINTER_NAME or install a printer driver.");
    }
    private static void send(PrintService printer, byte[] bytes) throws Exception {
        DocPrintJob job = printer.createPrintJob();
        Doc doc = new SimpleDoc(bytes, DocFlavor.BYTE_ARRAY.AUTOSENSE, null);
        job.print(doc, null);
    }
    private static String value(JsonNode node, String key) { JsonNode v = node.path(key); return v.isMissingNode() || v.isNull() ? "-" : v.asText(); }
    private static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n); }
}
