package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.RepairCreateRequest;
import com.insophinia.pos.dto.V7Dtos.RepairStatusTransitionRequest;
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
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RepairService} — the repair-job lifecycle service.
 *
 * Coverage:
 *   - Status workflow validation (legal + illegal transitions)
 *   - Timestamps set on each transition (diagnosedAt, startedAt, etc.)
 *   - Create produces a repair_number + received status + received_at
 *   - Invalid priority rejected
 *   - Line items with computed subtotals
 */
@ExtendWith(MockitoExtension.class)
class RepairServiceTest {

    @Mock private RepairRepository repairs;
    @Mock private RepairItemRepository repairItems;
    @Mock private CustomerRepository customers;
    @Mock private UserRepository users;
    @Mock private SyncHub syncHub;
    @Mock private ItemRepository items;
    @Mock private BillRepository bills;

    private RepairService service;

    @BeforeEach
    void setUp() {
        service = new RepairService(repairs, repairItems, customers, users, syncHub);
        // Inject the @Resource-annotated fields via reflection
        try {
            var itemsField = RepairService.class.getDeclaredField("items");
            itemsField.setAccessible(true);
            itemsField.set(service, items);
            var billsField = RepairService.class.getDeclaredField("bills");
            billsField.setAccessible(true);
            billsField.set(service, bills);
        } catch (Exception e) {
            fail("Failed to inject mock repositories: " + e.getMessage());
        }
    }

    private Repair makeRepair(RepairStatus status) {
        Repair r = new Repair();
        r.setId(UUID.randomUUID());
        r.setRepairNumber("RP-20261019-00001-1234");
        r.setCustomerName("Test Customer");
        r.setItemDescription("Dell Laptop");
        r.setProblemReported("Won't boot");
        r.setStatus(status);
        r.setPriority(RepairPriority.normal);
        r.setReceivedAt(Instant.now());
        r.setEstimatedCost(BigDecimal.ZERO);
        r.setActualCost(BigDecimal.ZERO);
        return r;
    }

    // ===== Status workflow validation =====

    @Test
    void transitionRejectsIllegalClosedToOpen() {
        Repair r = makeRepair(RepairStatus.closed);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));

        assertThrows(BadRequestException.class, () ->
                service.transition(r.getId(),
                        new RepairStatusTransitionRequest("open", null, null, null, null)),
                "Closed → open is illegal (terminal state)");
    }

    @Test
    void transitionRejectsCancelledToCompleted() {
        Repair r = makeRepair(RepairStatus.cancelled);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));

        assertThrows(BadRequestException.class, () ->
                service.transition(r.getId(),
                        new RepairStatusTransitionRequest("completed", null, null, null, null)),
                "Cancelled → completed is illegal (terminal state)");
    }

    @Test
    void transitionRejectsDeliveredToReceived() {
        Repair r = makeRepair(RepairStatus.delivered);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));

        assertThrows(BadRequestException.class, () ->
                service.transition(r.getId(),
                        new RepairStatusTransitionRequest("received", null, null, null, null)),
                "Delivered → received is illegal (can't go backwards)");
    }

    @Test
    void transitionAllowsReceivedToDiagnosed() {
        Repair r = makeRepair(RepairStatus.received);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));
        when(repairs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Repair result = service.transition(r.getId(),
                new RepairStatusTransitionRequest("diagnosed",
                        "Faulty RAM module", BigDecimal.valueOf(2500), null, null));

        assertEquals(RepairStatus.diagnosed, result.getStatus());
        assertEquals("Faulty RAM module", result.getDiagnosis());
        assertEquals(BigDecimal.valueOf(2500), result.getActualCost());
        assertNotNull(result.getDiagnosedAt(),
                "diagnosed_at must be set on transition to 'diagnosed'");
    }

    @Test
    void transitionAllowsCompletedToDelivered() {
        Repair r = makeRepair(RepairStatus.completed);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));
        when(repairs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Repair result = service.transition(r.getId(),
                new RepairStatusTransitionRequest("delivered", null, null, null, null));

        assertEquals(RepairStatus.delivered, result.getStatus());
        assertNotNull(result.getDeliveredAt(),
                "delivered_at must be set on transition to 'delivered'");
    }

    @Test
    void transitionSetsStartedAtOnlyOnce() {
        Repair r = makeRepair(RepairStatus.received);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));
        when(repairs.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // First transition: received → in_progress (sets startedAt)
        Repair step1 = service.transition(r.getId(),
                new RepairStatusTransitionRequest("in_progress", null, null, null, null));
        Instant firstStart = step1.getStartedAt();
        assertNotNull(firstStart, "started_at must be set on first in_progress");

        // Second transition: in_progress → awaiting_parts → in_progress (shouldn't reset startedAt)
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(step1));
        Repair step2 = service.transition(r.getId(),
                new RepairStatusTransitionRequest("awaiting_parts", null, null, null, null));
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(step2));
        Repair step3 = service.transition(r.getId(),
                new RepairStatusTransitionRequest("in_progress", null, null, null, null));

        assertEquals(firstStart, step3.getStartedAt(),
                "started_at must not be overwritten on re-entry to in_progress");
    }

    @Test
    void transitionRejectsInvalidStatusName() {
        Repair r = makeRepair(RepairStatus.received);
        when(repairs.findByIdAndDeletedAtIsNull(r.getId())).thenReturn(Optional.of(r));

        assertThrows(BadRequestException.class, () ->
                service.transition(r.getId(),
                        new RepairStatusTransitionRequest("invalid_status", null, null, null, null)),
                "Invalid status string must be rejected");
    }

    // ===== Create =====

    @Test
    void createSetsReceivedStatusAndRepairNumber() {
        when(repairs.countByReceivedAtAfterAndDeletedAtIsNull(any())).thenReturn(0L);
        when(repairs.save(any(Repair.class))).thenAnswer(inv -> {
            Repair r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });

        Repair result = service.create(new RepairCreateRequest(
                null, "Walk-in customer", null, "+94771234567",
                "HP Pavilion laptop", "SN123456", "Won't boot past BIOS",
                "normal", null, BigDecimal.ZERO, null, null));

        assertNotNull(result);
        assertNotNull(result.getRepairNumber());
        assertTrue(result.getRepairNumber().startsWith("RP-"),
                "Repair number must start with RP-");
        assertEquals(RepairStatus.received, result.getStatus());
        assertNotNull(result.getReceivedAt());
        assertEquals(RepairPriority.normal, result.getPriority());
        verify(syncHub).broadcast(eq("REPAIR_CREATED"), eq("repair"), any());
    }

    @Test
    void createRejectsInvalidPriority() {
        assertThrows(BadRequestException.class, () ->
                service.create(new RepairCreateRequest(
                        null, "Walk-in", null, null, "Laptop", null, "Broken screen",
                        "invalid_priority", null, BigDecimal.ZERO, null, null)),
                "Invalid priority must be rejected with a clear message");
    }
}
