package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.RepairCreateRequest;
import com.insophinia.pos.dto.V7Dtos.RepairItemRequest;
import com.insophinia.pos.dto.V7Dtos.RepairStatusTransitionRequest;
import com.insophinia.pos.entity.Repair;
import com.insophinia.pos.entity.RepairItem;
import com.insophinia.pos.entity.RepairLineType;
import com.insophinia.pos.entity.RepairPriority;
import com.insophinia.pos.entity.RepairStatus;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.RepairItemRepository;
import com.insophinia.pos.repository.RepairRepository;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.security.AuthUser;
import com.insophinia.pos.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repair job lifecycle — receive, diagnose, progress, deliver, close.
 *
 * Status workflow (validated in {@link #validateStatusTransition}):
 *   received          → diagnosed | in_progress | rejected | cancelled
 *   diagnosed         → in_progress | awaiting_parts | rejected | cancelled
 *   in_progress       → awaiting_parts | completed | cancelled
 *   awaiting_parts    → in_progress | cancelled
 *   completed         → delivered
 *   delivered         → closed
 *   closed, rejected, cancelled → terminal (no further transitions)
 *
 * Each transition timestamps the matching field (diagnosedAt, startedAt,
 * completedAt, deliveredAt, closedAt) for SLA reporting.
 */
@Service
public class RepairService {

    private final RepairRepository repairs;
    private final RepairItemRepository repairItems;
    private final CustomerRepository customers;
    private final UserRepository users;
    private final SyncHub syncHub;

    public RepairService(RepairRepository repairs, RepairItemRepository repairItems,
                          CustomerRepository customers, UserRepository users, SyncHub syncHub) {
        this.repairs = repairs;
        this.repairItems = repairItems;
        this.customers = customers;
        this.users = users;
        this.syncHub = syncHub;
    }

    @Transactional(readOnly = true)
    public Page<Repair> list(String search, RepairStatus status, UUID customerId,
                             UUID technicianId, PageRequest pageable) {
        Specification<Repair> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        if (customerId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("customer").get("id"), customerId));
        if (technicianId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("technician").get("id"), technicianId));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("repairNumber")), like),
                    cb.like(cb.lower(root.get("customerName")), like),
                    cb.like(cb.lower(root.get("itemDescription")), like),
                    cb.like(cb.lower(root.get("itemSerial")), like)));
        }
        return repairs.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Repair get(UUID id) {
        return repairs.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Repair not found"));
    }

    @Transactional(readOnly = true)
    public List<RepairItem> listItems(UUID repairId) {
        return repairItems.findByRepairIdOrderByCreatedAtAsc(repairId);
    }

    @Transactional
    public Repair create(RepairCreateRequest req) {
        Repair r = new Repair();
        r.setRepairNumber(generateRepairNumber());
        if (req.customerId() != null) {
            r.setCustomer(customers.findByIdAndDeletedAtIsNull(req.customerId()).orElseThrow(
                    () -> new BadRequestException("Customer not found")));
        }
        r.setCustomerName(req.customerName());
        r.setCustomerEmail(req.customerEmail());
        r.setCustomerPhone(req.customerPhone());
        r.setItemDescription(req.itemDescription());
        r.setItemSerial(req.itemSerial());
        r.setProblemReported(req.problemReported());
        try {
            r.setPriority(req.priority() == null || req.priority().isBlank()
                    ? RepairPriority.normal
                    : RepairPriority.valueOf(req.priority()));
        } catch (Exception e) {
            throw new BadRequestException("Invalid priority: " + req.priority());
        }
        if (req.technicianId() != null) {
            User tech = users.findByIdAndDeletedAtIsNull(req.technicianId()).orElse(null);
            r.setTechnician(tech);
            if (tech != null) r.setTechnicianName(tech.getFirstName() + " " + tech.getLastName());
        }
        r.setEstimatedCost(req.estimatedCost() == null ? BigDecimal.ZERO : req.estimatedCost());
        r.setStatus(RepairStatus.received);
        r.setReceivedAt(Instant.now());
        r.setNotes(req.notes());
        Repair saved = repairs.save(r);

        // Save line items (initial diagnosis or empty list — technician adds later)
        if (req.items() != null) {
            for (RepairItemRequest item : req.items()) {
                RepairItem ri = new RepairItem();
                ri.setRepair(saved);
                ri.setLineType(parseLineType(item.lineType()));
                ri.setDescription(item.description());
                if (item.itemId() != null) {
                    ri.setItem(items.findById(item.itemId()).orElse(null));
                }
                ri.setQuantity(item.quantity() == null ? BigDecimal.ONE : item.quantity());
                ri.setUnitPrice(item.unitPrice() == null ? BigDecimal.ZERO : item.unitPrice());
                ri.setDiscount(item.discount() == null ? BigDecimal.ZERO : item.discount());
                ri.setNotes(item.notes());
                computeSubtotal(ri);
                repairItems.save(ri);
            }
        }
        syncHub.broadcast("REPAIR_CREATED", "repair",
                java.util.Map.of("repairId", saved.getId().toString(), "repairNumber", saved.getRepairNumber()));
        return saved;
    }

    @Transactional
    public Repair transition(UUID repairId, RepairStatusTransitionRequest req) {
        Repair r = get(repairId);
        RepairStatus nextStatus;
        try {
            nextStatus = RepairStatus.valueOf(req.nextStatus());
        } catch (Exception e) {
            throw new BadRequestException("Invalid status: " + req.nextStatus());
        }
        validateStatusTransition(r.getStatus(), nextStatus);

        Instant now = Instant.now();
        r.setStatus(nextStatus);
        if (req.diagnosis() != null) r.setDiagnosis(req.diagnosis());
        if (req.actualCost() != null) r.setActualCost(req.actualCost());
        if (req.finalBillId() != null) {
            r.setFinalBill(bills.findById(req.finalBillId()).orElse(null));
        }
        if (req.warrantyMonths() != null && req.warrantyMonths() > 0) {
            r.setWarrantyMonths(req.warrantyMonths());
        }

        switch (nextStatus) {
            case diagnosed -> r.setDiagnosedAt(now);
            case in_progress -> { if (r.getStartedAt() == null) r.setStartedAt(now); }
            case completed -> r.setCompletedAt(now);
            case delivered -> r.setDeliveredAt(now);
            case closed -> r.setClosedAt(now);
            default -> { /* rejected / cancelled / awaiting_parts have no timestamp */ }
        }

        Repair saved = repairs.save(r);
        syncHub.broadcast("REPAIR_UPDATED", "repair",
                java.util.Map.of("repairId", saved.getId().toString(), "status", nextStatus.name()));
        return saved;
    }

    @Transactional
    public RepairItem addLineItem(UUID repairId, RepairItemRequest req) {
        Repair r = get(repairId);
        RepairItem ri = new RepairItem();
        ri.setRepair(r);
        ri.setLineType(parseLineType(req.lineType()));
        ri.setDescription(req.description());
        if (req.itemId() != null) ri.setItem(items.findById(req.itemId()).orElse(null));
        ri.setQuantity(req.quantity() == null ? BigDecimal.ONE : req.quantity());
        ri.setUnitPrice(req.unitPrice() == null ? BigDecimal.ZERO : req.unitPrice());
        ri.setDiscount(req.discount() == null ? BigDecimal.ZERO : req.discount());
        ri.setNotes(req.notes());
        computeSubtotal(ri);
        return repairItems.save(ri);
    }

    @Transactional
    public void delete(UUID id) {
        Repair r = get(id);
        r.setDeletedAt(Instant.now());
        repairs.save(r);
    }

    /** Re-compute the repair's actual_cost = Σ(line.subtotal). */
    @Transactional
    public Repair recomputeActualCost(UUID repairId) {
        Repair r = get(repairId);
        List<RepairItem> items = repairItems.findByRepairIdOrderByCreatedAtAsc(repairId);
        BigDecimal total = BigDecimal.ZERO;
        for (RepairItem ri : items) {
            total = total.add(ri.getSubtotal());
        }
        r.setActualCost(total.setScale(2, RoundingMode.HALF_UP));
        return repairs.save(r);
    }

    // ----- helpers ------------------------------------------------------

    private void validateStatusTransition(RepairStatus from, RepairStatus to) {
        if (from == to) return;
        boolean allowed = switch (from) {
            case received -> to == RepairStatus.diagnosed
                    || to == RepairStatus.in_progress
                    || to == RepairStatus.rejected
                    || to == RepairStatus.cancelled;
            case diagnosed -> to == RepairStatus.in_progress
                    || to == RepairStatus.awaiting_parts
                    || to == RepairStatus.rejected
                    || to == RepairStatus.cancelled;
            case in_progress -> to == RepairStatus.awaiting_parts
                    || to == RepairStatus.completed
                    || to == RepairStatus.cancelled;
            case awaiting_parts -> to == RepairStatus.in_progress
                    || to == RepairStatus.cancelled;
            case completed -> to == RepairStatus.delivered;
            case delivered -> to == RepairStatus.closed;
            case closed, rejected, cancelled -> false; // terminal
        };
        if (!allowed) {
            throw new BadRequestException("Invalid repair status transition: " + from + " → " + to);
        }
    }

    private RepairLineType parseLineType(String s) {
        try {
            return RepairLineType.valueOf(s);
        } catch (Exception e) {
            throw new BadRequestException("Invalid line_type: " + s);
        }
    }

    private void computeSubtotal(RepairItem ri) {
        BigDecimal qty = ri.getQuantity() == null ? BigDecimal.ONE : ri.getQuantity();
        BigDecimal price = ri.getUnitPrice() == null ? BigDecimal.ZERO : ri.getUnitPrice();
        BigDecimal disc = ri.getDiscount() == null ? BigDecimal.ZERO : ri.getDiscount();
        ri.setSubtotal(qty.multiply(price).subtract(disc).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    private String generateRepairNumber() {
        // RP-YYYYMMDD-NNNNN (sequential within day; falls back to random suffix
        // for collision avoidance under concurrent inserts)
        long todayCount = repairs.countByReceivedAtAfterAndDeletedAtIsNull(
                java.time.LocalDate.now(java.time.ZoneOffset.UTC).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
        int randSuffix = java.util.concurrent.ThreadLocalRandom.current().nextInt(1000, 9999);
        return "RP-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
                + "-" + String.format("%05d", todayCount + 1) + "-" + randSuffix;
    }

    // Backing fields injected lazily so the service compiles without forcing
    // them into the constructor (the repair form has these as optional).
    @jakarta.annotation.Resource private com.insophinia.pos.repository.ItemRepository items;
    @jakarta.annotation.Resource private com.insophinia.pos.repository.BillRepository bills;
}
