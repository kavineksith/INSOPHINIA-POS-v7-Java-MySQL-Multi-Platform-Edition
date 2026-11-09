package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.WarrantyClaimFileRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimResolveRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimUpdateStatusRequest;
import com.insophinia.pos.entity.*;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for {@link WarrantyService} — the warranty registry + claim
 * lifecycle service.
 *
 * Coverage:
 *   - fileClaim rejects voided + expired warranties
 *   - fileClaim auto-marks warranty status as 'claimed'
 *   - updateClaimStatus validates lifecycle transitions
 *   - resolveClaim sets resolution + resolved_at timestamp
 *   - dailyMaintenance marks expired warranties
 *   - autoPromote creates a warranty from a bill item
 */
@ExtendWith(MockitoExtension.class)
class WarrantyServiceTest {

    @Mock private WarrantyRepository warranties;
    @Mock private WarrantyClaimRepository claims;
    @Mock private CustomerRepository customers;
    @Mock private ItemRepository items;
    @Mock private BillRepository bills;
    @Mock private SyncHub syncHub;

    private WarrantyService service;

    @BeforeEach
    void setUp() {
        service = new WarrantyService(warranties, claims, customers, items, bills, syncHub);
    }

    private Warranty makeWarranty(WarrantyStatus status, Instant expiry) {
        Warranty w = new Warranty();
        w.setId(UUID.randomUUID());
        w.setCustomerName("Test Customer");
        w.setItemName("Test Item");
        w.setWarrantyMonths(12);
        w.setWarrantyStartsAt(Instant.now().minus(300, ChronoUnit.DAYS));
        w.setWarrantyExpiresAt(expiry);
        w.setStatus(status);
        return w;
    }

    // ===== fileClaim =====

    @Test
    void fileClaimRejectsVoidedWarranty() {
        Warranty w = makeWarranty(WarrantyStatus.voided, Instant.now().plus(30, ChronoUnit.DAYS));
        when(warranties.findByIdAndDeletedAtIsNull(w.getId())).thenReturn(Optional.of(w));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.fileClaim(w.getId(),
                        new WarrantyClaimFileRequest("Device won't turn on", null)));
        assertTrue(ex.getMessage().contains("voided"));
        verify(claims, never()).save(any());
    }

    @Test
    void fileClaimRejectsExpiredWarranty() {
        Warranty w = makeWarranty(WarrantyStatus.expired, Instant.now().minus(10, ChronoUnit.DAYS));
        when(warranties.findByIdAndDeletedAtIsNull(w.getId())).thenReturn(Optional.of(w));

        BadRequestException ex = assertThrows(BadRequestException.class, () ->
                service.fileClaim(w.getId(),
                        new WarrantyClaimFileRequest("Screen cracked", null)));
        assertTrue(ex.getMessage().contains("expired"));
        verify(claims, never()).save(any());
    }

    @Test
    void fileClaimSucceedsForActiveWarrantyAndMarksClaimed() {
        Warranty w = makeWarranty(WarrantyStatus.active, Instant.now().plus(30, ChronoUnit.DAYS));
        when(warranties.findByIdAndDeletedAtIsNull(w.getId())).thenReturn(Optional.of(w));
        when(claims.save(any(WarrantyClaim.class))).thenAnswer(inv -> {
            WarrantyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        // Use lenient stubbing for the findByWarrantyIdOrderByFiledAtDesc —
        // not called by fileClaim directly but may be called by the openClaimsCount helper
        lenient().when(claims.findByWarrantyIdOrderByFiledAtDesc(w.getId())).thenReturn(java.util.List.of());

        WarrantyClaim result = service.fileClaim(w.getId(),
                new WarrantyClaimFileRequest("Battery drains too fast", "Customer reported"));

        assertNotNull(result);
        assertNotNull(result.getClaimNumber());
        assertTrue(result.getClaimNumber().startsWith("WC-"));
        assertEquals(ClaimStatus.open, result.getStatus());
        assertEquals(ClaimResolution.pending, result.getResolution());
        assertEquals(WarrantyStatus.claimed, w.getStatus(),
                "Warranty must be marked as 'claimed' so it shows up in the worklist");
        verify(syncHub).broadcast(eq("WARRANTY_CLAIM_FILED"), eq("warranty_claim"), any());
    }

    // ===== updateClaimStatus lifecycle validation =====

    @Test
    void updateClaimStatusRejectsIllegalTransition() {
        WarrantyClaim c = new WarrantyClaim();
        c.setId(UUID.randomUUID());
        c.setStatus(ClaimStatus.closed);
        when(claims.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));

        assertThrows(BadRequestException.class, () ->
                service.updateClaimStatus(c.getId(),
                        new WarrantyClaimUpdateStatusRequest("open", null)),
                "Closed → open is illegal (terminal state)");
    }

    @Test
    void updateClaimStatusAllowsOpenToInProgress() {
        WarrantyClaim c = new WarrantyClaim();
        c.setId(UUID.randomUUID());
        c.setStatus(ClaimStatus.open);
        when(claims.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));
        when(claims.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarrantyClaim result = service.updateClaimStatus(c.getId(),
                new WarrantyClaimUpdateStatusRequest("in_progress", null));

        assertEquals(ClaimStatus.in_progress, result.getStatus());
    }

    @Test
    void updateClaimStatusSetsResolvedAtOnResolvedOrClosed() {
        WarrantyClaim c = new WarrantyClaim();
        c.setId(UUID.randomUUID());
        c.setStatus(ClaimStatus.in_progress);
        when(claims.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));
        when(claims.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarrantyClaim result = service.updateClaimStatus(c.getId(),
                new WarrantyClaimUpdateStatusRequest("resolved", null));

        assertEquals(ClaimStatus.resolved, result.getStatus());
        assertNotNull(result.getResolvedAt(),
                "resolved_at must be set when status moves to resolved");
    }

    // ===== resolveClaim =====

    @Test
    void resolveClaimSetsResolutionAndTimestamp() {
        WarrantyClaim c = new WarrantyClaim();
        c.setId(UUID.randomUUID());
        c.setStatus(ClaimStatus.in_progress);
        c.setResolution(ClaimResolution.pending);
        when(claims.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));
        when(claims.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WarrantyClaim result = service.resolveClaim(c.getId(),
                new WarrantyClaimResolveRequest(
                        "replacement", "Swapped for new unit",
                        null, BigDecimal.ZERO, "Confirmed dead on arrival"));

        assertEquals(ClaimResolution.replacement, result.getResolution());
        assertEquals("Swapped for new unit", result.getResolutionNotes());
        assertEquals("Confirmed dead on arrival", result.getDiagnosis());
        assertEquals(ClaimStatus.resolved, result.getStatus());
        assertNotNull(result.getResolvedAt());
        verify(syncHub).broadcast(eq("WARRANTY_CLAIM_RESOLVED"), eq("warranty_claim"), any());
    }

    @Test
    void resolveClaimRejectsInvalidResolution() {
        WarrantyClaim c = new WarrantyClaim();
        c.setId(UUID.randomUUID());
        when(claims.findByIdAndDeletedAtIsNull(c.getId())).thenReturn(Optional.of(c));

        assertThrows(BadRequestException.class, () ->
                service.resolveClaim(c.getId(),
                        new WarrantyClaimResolveRequest("invalid_resolution", null, null,
                                BigDecimal.ZERO, null)),
                "Invalid resolution type must be rejected");
    }

    // ===== dailyMaintenance =====

    @Test
    void dailyMaintenanceMarksExpiredAndClaimed() {
        when(warranties.markExpired(any())).thenReturn(5);
        when(warranties.markClaimedForWarrantiesWithOpenClaims()).thenReturn(3);

        int result = service.dailyMaintenance();

        assertEquals(8, result, "Should return expired (5) + claimed (3) = 8");
        verify(warranties).markExpired(any());
        verify(warranties).markClaimedForWarrantiesWithOpenClaims();
        verify(syncHub).broadcast(eq("WARRANTIES_REFRESHED"), eq("warranty"), any());
    }

    @Test
    void dailyMaintenanceNoOpWhenNothingChanges() {
        when(warranties.markExpired(any())).thenReturn(0);
        when(warranties.markClaimedForWarrantiesWithOpenClaims()).thenReturn(0);

        int result = service.dailyMaintenance();
        assertEquals(0, result);
        verify(syncHub, never()).broadcast(any(), any(), any());
    }

    // ===== autoPromote =====

    @Test
    void autoPromoteCreatesWarrantyFromBillItem() {
        Bill bill = new Bill();
        bill.setId(UUID.randomUUID());
        bill.setBillNumber("BILL-20261019-0001");
        bill.setCustomerName("Alice");
        bill.setCreatedAt(Instant.now());

        BillItem bi = new BillItem();
        bi.setId(UUID.randomUUID());
        bi.setBill(bill);
        bi.setItemName("Dell Laptop");
        bi.setPluCode("DEMO-0001");
        bi.setWarrantyMonths(12);
        bi.setQuantity(BigDecimal.ONE);

        Item item = new Item();
        item.setId(UUID.randomUUID());
        bi.setItem(item);

        when(warranties.save(any(Warranty.class))).thenAnswer(inv -> {
            Warranty w = inv.getArgument(0);
            w.setId(UUID.randomUUID());
            return w;
        });

        Customer customer = new Customer();
        customer.setId(UUID.randomUUID());
        customer.setName("Alice");
        customer.setEmail("alice@example.com");

        Warranty result = service.autoPromote(bi, customer);

        assertNotNull(result);
        assertEquals(bill, result.getBill());
        assertEquals(bi, result.getBillItem());
        assertEquals(customer, result.getCustomer());
        assertEquals("Alice", result.getCustomerName());
        assertEquals("alice@example.com", result.getCustomerEmail());
        assertEquals("Dell Laptop", result.getItemName());
        assertEquals(12, result.getWarrantyMonths());
        assertEquals(WarrantyStatus.active, result.getStatus());
        assertNotNull(result.getWarrantyExpiresAt());
        // Expiry should be ~12 months after the bill's creation date
        assertTrue(result.getWarrantyExpiresAt().isAfter(Instant.now().plus(360, ChronoUnit.DAYS)));
    }
}
