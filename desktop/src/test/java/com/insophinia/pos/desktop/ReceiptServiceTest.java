package com.insophinia.pos.desktop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReceiptServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path temp;

    @Test void receiptIncludesBillLinesAndTotals() throws Exception {
        JsonNode bill = mapper.readTree("""
          {"billNumber":"POS-1001","createdAt":"2026-10-15T00:00:00Z","staffName":"Cashier",
           "customerName":"Walk-in","subtotal":100,"totalDiscount":5,"vatAmount":0,"ssclAmount":0,
           "taxTotal":0,"totalAmount":95,"paidAmount":100,"balance":5,"status":"completed",
           "items":[{"itemName":"Tea","quantity":2,"unitPrice":50,"subtotal":100}]}
          """);
        List<String> lines = ReceiptService.receiptLines(bill);
        assertTrue(lines.stream().anyMatch(x -> x.contains("POS-1001")));
        assertTrue(lines.stream().anyMatch(x -> x.contains("Tea")));
        assertTrue(lines.stream().anyMatch(x -> x.contains("TOTAL LKR: 95")));
    }

    @Test void pdfReceiptCanBeWritten() throws Exception {
        JsonNode bill = mapper.readTree("{\"billNumber\":\"POS-1002\",\"items\":[],\"totalAmount\":0}");
        Path output = temp.resolve("receipt.pdf");
        ReceiptService.savePdf(bill, output);
        assertTrue(java.nio.file.Files.size(output) > 100);
        assertEquals('%', (char) java.nio.file.Files.readAllBytes(output)[0]);
    }
}
