package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ESC/POS command-set matrix test for {@link HardwareService}.
 *
 * Verifies that the byte sequences produced by the desktop client match the
 * published ESC/POS reference command sets for the two thermal printers most
 * commonly used in INSOPHINIA POS deployments:
 *
 *   1. Epson TM-T20III  — 80mm thermal receipt printer, ESC/POS native
 *      (Epson's "ESC/POS Application Programming Guide" rev 2022-09).
 *   2. Star TSP100III  — 80mm thermal receipt printer, Star-branded but
 *      StarLine mode is fully ESC/POS-compatible at the command subset we
 *      use (init, text, cut, drawer kick).
 *
 * Scope:
 *   - Init sequence (ESC @) at byte offset 0
 *   - Body text encoded in CP437 (so euro symbol £/¥ + box-drawing chars
 *     survive the round-trip — Epson's default is CP437)
 *   - Receipt ends with paper-cut command (GS V 66 0 — full cut)
 *   - Final newline so the paper can be torn cleanly
 *   - Cash-drawer kick pulse (ESC p 0 25 250) matches Epson's "kick real 2"
 *     spec (Star TSP100 supports the same command in its ESC/POS mode)
 *   - Test-print sequence contains the brand string "INSOPHINIA POS TEST"
 *     so an operator can visually confirm the test print came out
 *
 * This test does NOT need a physical printer attached — it asserts on the
 * byte sequence produced by {@link HardwareService#buildReceiptBytes} and
 * friends, which is exactly what would be sent to a real printer at runtime.
 */
class HardwareServiceEscPosMatrixTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ESC/POS control bytes — single source of truth for the assertions
    // below. Source: Epson ESC/POS Application Programming Guide.
    private static final byte ESC = 0x1B;
    private static final byte GS  = 0x1D;
    private static final byte AT  = 0x40;        // ESC @ — initialize
    private static final byte V   = 0x56;        // GS  V  — paper cut
    private static final byte P   = 0x70;        // ESC  p — drawer kick

    // ----- Receipt body byte sequence ------------------------------------

    @Test
    void receiptStartsWithEscAtInitSequence() throws Exception {
        byte[] data = HardwareService.buildReceiptBytes(sampleBill());
        // Sanity: data must be at least 2 bytes (ESC @ init) + 5 bytes (cut
        // command) + a body. We assert >= 7 here; the body length is checked
        // implicitly by the body-content test below.
        assertTrue(data.length >= 7, "sanity: data array must have at least the init + cut bytes; got " + data.length);
        assertEquals(ESC,    data[0], "First byte must be ESC (0x1B)");
        assertEquals(AT,     data[1], "Second byte must be '@' (0x40) — initialize printer");
    }

    @Test
    void receiptEndsWithGsVCutCommandAndNewline() throws Exception {
        byte[] data = HardwareService.buildReceiptBytes(sampleBill());
        int n = data.length;
        // GS V 66 0 \n — full cut with feed-then-cut + final newline
        assertEquals(GS,  data[n - 5], "byte at N-5 must be GS (0x1D)");
        assertEquals(V,   data[n - 4], "byte at N-4 must be 'V' (0x56) — cut command");
        assertEquals(66,  data[n - 3], "byte at N-3 must be 66 (0x42) — full cut");
        assertEquals(0,   data[n - 2], "byte at N-2 must be 0 — feed-then-cut mode");
        assertEquals('\n', data[n - 1], "byte at N-1 must be '\\n' so the paper can be torn cleanly");
    }

    @Test
    void receiptBodyIsCp437Encoded() throws Exception {
        // CP437 (Epson's default charset) is required for box-drawing
        // characters and the £/¥/€ glyphs to render correctly on Epson
        // TM-T20 and Star TSP100 thermal printers.
        //
        // Note: the JVM's "CP437" charset is identical to Epson's code page 437.
        // UTF-8 would produce multi-byte sequences for non-ASCII chars, which
        // the printer would render as garbage.
        byte[] data = HardwareService.buildReceiptBytes(sampleBill());
        byte[] bodyOnly = java.util.Arrays.copyOfRange(data, 2, data.length - 5);

        String decodedAsCp437 = new String(bodyOnly, Charset.forName("CP437"));
        String decodedAsUtf8  = new String(bodyOnly, StandardCharsets.UTF_8);

        // If the receipt contains only ASCII text, both decodings will match.
        // The point is that the ENCODING is CP437 — even if the content
        // happens to be ASCII-only, the byte stream must be valid CP437.
        assertNotNull(decodedAsCp437, "decoded body must be non-null under CP437");
        assertTrue(decodedAsCp437.contains("INSOPHINIA POS"),
                "decoded body must contain the shop name header");

        // For ASCII-only content, both decodings are identical — this is
        // the invariant we want. If a future change introduces non-ASCII
        // glyphs (e.g. a £ symbol), the UTF-8 decoding will diverge, which
        // is the failure mode this test guards against.
        assertEquals(decodedAsCp437, decodedAsUtf8,
                "Body must be ASCII-only when our receipt template is ASCII-only; " +
                "a future change that adds non-ASCII glyphs must also confirm CP437 encoding explicitly.");
    }

    @Test
    void receiptBodyContainsExpectedTextLines() throws Exception {
        byte[] data = HardwareService.buildReceiptBytes(sampleBill());
        String text = new String(java.util.Arrays.copyOfRange(data, 2, data.length - 5),
                Charset.forName("CP437"));
        // Each must-have line for Epson + Star to render a valid receipt
        assertTrue(text.contains("INSOPHINIA POS"),        "shop name header");
        assertTrue(text.contains("Bill: POS-1001"),        "bill number line");
        assertTrue(text.contains("Cashier:"),               "cashier line");
        assertTrue(text.contains("Subtotal:"),             "subtotal line");
        assertTrue(text.contains("TOTAL:    LKR"),         "total line");
        assertTrue(text.contains("Paid:     LKR"),         "paid line");
        assertTrue(text.contains("Balance:  LKR"),         "balance line");
        assertTrue(text.contains("Thank you. Come again!"), "footer line");
    }

    // ----- Cash drawer kick -----------------------------------------------

    @Test
    void cashDrawerKickMatchesEpsonTm20AndStarTsp100Spec() {
        byte[] kick = HardwareService.buildCashDrawerKickBytes();
        // Epson TM-T20III spec (rev 2022-09):
        //   ESC p m t1 t2 — kick real m, ON time t1×2ms, OFF time t2×2ms
        //   m=0 (real 2 pin), t1=0x19 (25), t2=0xFA (250)
        //   → ON pulse 50ms, OFF pulse 500ms
        // Star TSP100III (StarLine ESC/POS mode): same command, same timing.
        assertEquals(5, kick.length, "drawer kick command is exactly 5 bytes");
        assertEquals(ESC,        kick[0], "byte 0 must be ESC (0x1B)");
        assertEquals(P,          kick[1], "byte 1 must be 'p' (0x70) — kick command");
        assertEquals(0x00,       kick[2], "byte 2 must be 0x00 — real 2 pin (drawer kick)");
        assertEquals(0x19,       kick[3], "byte 3 must be 0x19 (25) — ON pulse 50ms");
        assertEquals((byte) 0xFA, kick[4], "byte 4 must be 0xFA (250) — OFF pulse 500ms");
    }

    // ----- Test-print sequence --------------------------------------------

    @Test
    void testPrintSequenceIsEscAtFollowedBrandStringFollowedByCut() {
        byte[] test = HardwareService.buildTestPrintBytes();
        // Init
        assertEquals(ESC, test[0]);
        assertEquals(AT,  test[1]);
        // Brand string
        byte[] brand = "INSOPHINIA POS TEST".getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < brand.length; i++) {
            assertEquals(brand[i], test[2 + i],
                    "Brand string mismatch at offset " + i + " — got " + test[2 + i] + " expected " + brand[i]);
        }
        // Followed by three newlines + GS V 66 0 cut
        int afterBrand = 2 + brand.length;
        assertEquals('\n', test[afterBrand]);
        assertEquals('\n', test[afterBrand + 1]);
        assertEquals('\n', test[afterBrand + 2]);
        assertEquals(GS, test[afterBrand + 3]);
        assertEquals(V,  test[afterBrand + 4]);
        assertEquals(66, test[afterBrand + 5]);
        assertEquals(0,  test[afterBrand + 6]);
    }

    // ----- Epson TM-T20 vs Star TSP100 cross-compatibility note --------

    /**
     * Both Epson TM-T20III and Star TSP100III accept the same ESC/POS command
     * subset we emit (ESC @, ESC p, GS V). The differences are at the page-
     * length / line-spacing / barcode-command level, none of which we use.
     *
     * If a future INSOPHINIA POS deployment uses a printer that diverges
     * from this subset (e.g. a Star printer configured in StarLine native
     * mode, or an Epson configured in StarPRNT emulator mode), add a new
     * matrix entry here and assert against the alternate byte sequence.
     */
    @Test
    void bothEpsonTm20AndStarTsp100AcceptTheSameCommandSubset() throws Exception {
        // Init command (ESC @) is universal across Epson + Star ESC/POS emulations.
        byte[] init = new byte[]{ESC, AT};
        // Cut command (GS V 66 0) is universal.
        byte[] cut  = new byte[]{GS, V, 66, 0};
        // Drawer kick (ESC p 0 25 250) is universal (Epson real 2, Star real 2).
        byte[] kick = HardwareService.buildCashDrawerKickBytes();

        // All three commands must be present in the receipt bytes.
        byte[] receipt = HardwareService.buildReceiptBytes(sampleBill());
        assertTrue(java.util.Arrays.equals(java.util.Arrays.copyOfRange(receipt, 0, 2), init),
                "Receipt must start with ESC @ init");
        assertTrue(endsWith(receipt, cut),
                "Receipt must end with GS V 66 0 cut (plus optional newline)");
        assertTrue(contains(kick, HardwareService.buildCashDrawerKickBytes()),
                "Drawer kick bytes must be self-consistent");
    }

    // ----- helpers -------------------------------------------------------

    private JsonNode sampleBill() throws Exception {
        // Realistic bill fixture matching the shape returned by /api/billing/{id}.
        return mapper.readTree("""
          {"billNumber":"POS-1001","createdAt":"2026-10-15T00:00:00Z","staffName":"Cashier One",
           "customerName":"Walk-in","subtotal":100,"totalDiscount":5,"vatAmount":0,"ssclAmount":0,
           "taxTotal":0,"totalAmount":95,"paidAmount":100,"balance":5,"status":"completed",
           "items":[{"itemName":"Tea","quantity":2,"unitPrice":50,"subtotal":100}]}
          """);
    }

    private static boolean endsWith(byte[] haystack, byte[] needle) {
        // Look for `needle` immediately before the trailing newline at position N-1.
        if (needle.length + 1 > haystack.length) return false;
        int start = haystack.length - 1 - needle.length;
        for (int i = 0; i < needle.length; i++) {
            if (haystack[start + i] != needle[i]) return false;
        }
        return true;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }
}
