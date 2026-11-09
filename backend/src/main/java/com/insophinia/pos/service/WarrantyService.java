package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.WarrantyClaimFileRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimResolveRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyClaimUpdateStatusRequest;
import com.insophinia.pos.dto.V7Dtos.WarrantyCreateRequest;
import com.insophinia.pos.entity.ClaimResolution;
import com.insophinia.pos.entity.ClaimStatus;
import com.insophinia.pos.entity.Warranty;
import com.insophinia.pos.entity.WarrantyClaim;
import com.insophinia.pos.entity.WarrantyStatus;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.BillRepository;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.WarrantyClaimRepository;
import com.insophinia.pos.repository.WarrantyRepository;
import com.insophinia.pos.security.AuthUser;
import com.insophinia.pos.security.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Warranty + warranty-claim workflow.
 *
 * Lifecycle:
 *   1. Warranty auto-promoted on bill creation when a BillItem has
 *      warrantyMonths > 0 (called from BillingService.createBill).
 *   2. Manual warranty creation supported for items sold before v7 (no bill).
 *   3. Customer files a claim → open
 *   4. Technician moves to in_progress, diagnoses, sets resolution
 *      (repair / replacement / refund / rejected / escalated)
 *   5. Resolution applied → status=resolved + resolved_at set
 *   6. Closed after customer verification → status=closed
 *
 * Daily cron job (in application.yml `@Scheduled`):
 *   - Marks expired (status active → expired when warrantyExpiresAt < now)
 *   - Marks claimed (active → claimed when N+ open claims exist)
 */
@Service
public class WarrantyService {

    private final WarrantyRepository warranties;
    private final WarrantyClaimRepository claims;
    private final CustomerRepository customers;
    private final ItemRepository items;
    private final BillRepository bills;
    private final SyncHub syncHub;
    private final SecureRandom rng = new SecureRandom();

    public WarrantyService(WarrantyRepository warranties, WarrantyClaimRepository claims,
                           CustomerRepository customers, ItemRepository items,
                           BillRepository bills, SyncHub syncHub) {
        this.warranties = warranties;
        this.claims = claims;
        this.customers = customers;
        this.items = items;
        this.bills = bills;
        this.syncHub = syncHub;
    }

    // ----- Warranty CRUD -------------------------------------------------

    @Transactional(readOnly = true)
    public Page<Warranty> list(String search, WarrantyStatus status, UUID customerId, PageRequest pageable) {
        Specification<Warranty> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (status != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        if (customerId != null) spec = spec.and((root, q, cb) -> cb.equal(root.get("customer").get("id"), customerId));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(
                    cb.like(cb.lower(root.get("customerName")), like),
                    cb.like(cb.lower(root.get("itemName")), like),
                    cb.like(cb.lower(root.get("serialNumber")), like)));
        }
        return warranties.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Warranty get(UUID id) {
        return warranties.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty not found"));
    }

    @Transactional
    public Warranty createManual(WarrantyCreateRequest req) {
        Warranty w = new Warranty();
        w.setCustomerName(req.customerName());
        w.setCustomerEmail(req.customerEmail());
        w.setCustomerPhone(req.customerPhone());
        if (req.customerId() != null) {
            w.setCustomer(customers.findByIdAndDeletedAtIsNull(req.customerId()).orElseThrow(
                    () -> new BadRequestException("Customer not found")));
        }
        w.setItemName(req.itemName());
        w.setPluCode(req.pluCode());
        w.setSerialNumber(req.serialNumber());
        if (req.itemId() != null) {
            w.setItem(items.findById(req.itemId()).orElse(null));
        }
        w.setWarrantyMonths(req.warrantyMonths());
        Instant now = Instant.now();
        w.setWarrantyStartsAt(now);
        w.setWarrantyExpiresAt(now.atZone(java.time.ZoneOffset.UTC)
                .plusMonths(req.warrantyMonths()).toInstant());
        w.setStatus(WarrantyStatus.active);
        w.setNotes(req.notes());
        return warranties.save(w);
    }

    /**
     * Auto-promote a warranty from a bill item. Called by BillingService
     * when a bill is created with at least one item whose warrantyMonths > 0.
     */
    @Transactional
    public Warranty autoPromote(com.insophinia.pos.entity.BillItem bi,
                                 com.insophinia.pos.entity.Customer customer) {
        Warranty w = new Warranty();
        w.setBill(bi.getBill());
        w.setBillItem(bi);
        if (customer != null) {
            w.setCustomer(customer);
            w.setCustomerName(customer.getName());
            w.setCustomerEmail(customer.getEmail());
            w.setCustomerPhone(customer.getPhone());
        } else {
            w.setCustomerName(bi.getBill().getCustomerName());
            w.setCustomerEmail(bi.getBill().getCustomerEmail());
        }
        w.setItem(bi.getItem());
        w.setItemName(bi.getItemName());
        w.setPluCode(bi.getPluCode());
        w.setWarrantyMonths(bi.getWarrantyMonths());
        Instant start = bi.getBill().getCreatedAt() != null ? bi.getBill().getCreatedAt() : Instant.now();
        w.setWarrantyStartsAt(start);
        w.setWarrantyExpiresAt(start.atZone(java.time.ZoneOffset.UTC)
                .plusMonths(bi.getWarrantyMonths()).toInstant());
        w.setStatus(WarrantyStatus.active);
        return warranties.save(w);
    }

    @Transactional
    public void voidWarranty(UUID id, String reason) {
        Warranty w = get(id);
        w.setStatus(WarrantyStatus.voided);
        w.setNotes((w.getNotes() == null ? "" : w.getNotes() + "\n\n")
                + "Voided on " + Instant.now() + " — reason: " + reason);
        warranties.save(w);
        syncHub.broadcast("WARRANTY_UPDATED", "warranty", java.util.Map.of("warrantyId", id.toString()));
    }

    // ----- Claims --------------------------------------------------------

    @Transactional(readOnly = true)
    public java.util.List<WarrantyClaim> listClaims(UUID warrantyId) {
        // Sanity-check the warranty exists
        get(warrantyId);
        return claims.findByWarrantyIdOrderByFiledAtDesc(warrantyId);
    }

    @Transactional
    public WarrantyClaim fileClaim(UUID warrantyId, WarrantyClaimFileRequest req) {
        Warranty w = get(warrantyId);
        if (w.getStatus() == WarrantyStatus.voided) {
            throw new BadRequestException("Cannot file a claim against a voided warranty");
        }
        if (w.getStatus() == WarrantyStatus.expired) {
            throw new BadRequestException("Cannot file a claim against an expired warranty — escalate via admin");
        }

        WarrantyClaim c = new WarrantyClaim();
        c.setWarranty(w);
        c.setCustomer(w.getCustomer());
        c.setCustomerName(w.getCustomerName());
        c.setClaimNumber(generateClaimNumber());
        c.setFaultReported(req.faultReported());
        c.setStatus(ClaimStatus.open);
        c.setFiledAt(Instant.now());
        c.setResolution(ClaimResolution.pending);

        WarrantyClaim saved = claims.save(c);

        // Mark the warranty as 'claimed' so it shows up in the worklist.
        if (w.getStatus() == WarrantyStatus.active || w.getStatus() == WarrantyStatus.expiring) {
            w.setStatus(WarrantyStatus.claimed);
            warranties.save(w);
        }
        syncHub.broadcast("WARRANTY_CLAIM_FILED", "warranty_claim",
                java.util.Map.of("claimId", saved.getId().toString(), "warrantyId", warrantyId.toString()));
        return saved;
    }

    @Transactional
    public WarrantyClaim updateClaimStatus(UUID claimId, WarrantyClaimUpdateStatusRequest req) {
        WarrantyClaim c = claims.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty claim not found"));
        ClaimStatus nextStatus;
        try {
            nextStatus = ClaimStatus.valueOf(req.status());
        } catch (Exception e) {
            throw new BadRequestException("Invalid status: " + req.status());
        }
        validateStatusTransition(c.getStatus(), nextStatus);

        c.setStatus(nextStatus);
        if (req.technicianNotes() != null) {
            AuthUser auth = SecurityUtils.requireUser();
            c.setDiagnosis(req.technicianNotes());
            c.setTechnicianName(auth.username());
        }
        if (nextStatus == ClaimStatus.resolved || nextStatus == ClaimStatus.closed) {
            c.setResolvedAt(Instant.now());
        }
        WarrantyClaim saved = claims.save(c);
        syncHub.broadcast("WARRANTY_CLAIM_UPDATED", "warranty_claim",
                java.util.Map.of("claimId", saved.getId().toString(), "status", nextStatus.name()));
        return saved;
    }

    @Transactional
    public WarrantyClaim resolveClaim(UUID claimId, WarrantyClaimResolveRequest req) {
        WarrantyClaim c = claims.findByIdAndDeletedAtIsNull(claimId)
                .orElseThrow(() -> new ResourceNotFoundException("Warranty claim not found"));

        ClaimResolution resolution;
        try {
            resolution = ClaimResolution.valueOf(req.resolution());
        } catch (Exception e) {
            throw new BadRequestException("Invalid resolution: " + req.resolution());
        }
        c.setResolution(resolution);
        if (req.diagnosis() != null) c.setDiagnosis(req.diagnosis());
        c.setResolutionNotes(req.resolutionNotes());
        if (req.replacementItemId() != null) {
            c.setReplacementItem(items.findById(req.replacementItemId()).orElse(null));
        }
        c.setRefundAmount(req.refundAmount() == null ? java.math.BigDecimal.ZERO : req.refundAmount());
        c.setStatus(ClaimStatus.resolved);
        c.setResolvedAt(Instant.now());
        WarrantyClaim saved = claims.save(c);
        syncHub.broadcast("WARRANTY_CLAIM_RESOLVED", "warranty_claim",
                java.util.Map.of("claimId", saved.getId().toString(), "resolution", resolution.name()));
        return saved;
    }

    // ----- Cron helpers --------------------------------------------------

    /**
     * Run by the daily cron job (or manually via the admin endpoint):
     * mark all warranties past their expiry as 'expired', and any warranty
     * with N+ open claims as 'claimed'.
     */
    @Transactional
    public int dailyMaintenance() {
        int expired = warranties.markExpired(Instant.now());
        int claimed = warranties.markClaimedForWarrantiesWithOpenClaims();
        if (expired + claimed > 0) {
            syncHub.broadcast("WARRANTIES_REFRESHED", "warranty",
                    java.util.Map.of("expired", expired, "claimed", claimed));
        }
        return expired + claimed;
    }

    // ----- helpers ------------------------------------------------------

    /** Validate lifecycle: open → in_progress → resolved/rejected/closed. */
    private void validateStatusTransition(ClaimStatus from, ClaimStatus to) {
        if (from == to) return;
        boolean allowed = switch (from) {
            case open -> to == ClaimStatus.in_progress || to == ClaimStatus.rejected || to == ClaimStatus.closed;
            case in_progress -> to == ClaimStatus.resolved || to == ClaimStatus.rejected || to == ClaimStatus.closed;
            case resolved -> to == ClaimStatus.closed;
            case rejected, closed -> false; // terminal states
        };
        if (!allowed) {
            throw new BadRequestException("Invalid claim status transition: " + from + " → " + to);
        }
    }

    private String generateClaimNumber() {
        // WC-YYYYMMDD-XXXX (4 random hex chars)
        byte[] b = new byte[2];
        rng.nextBytes(b);
        String suffix = String.format("%02x%02x", b[0], b[1]);
        return "WC-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "") + "-" + suffix;
    }

    public static int openClaimsCount(java.util.UUID warrantyId, WarrantyClaimRepository repo) {
        return (int) repo.findByWarrantyIdOrderByFiledAtDesc(warrantyId).stream()
                .filter(c -> c.getStatus() == com.insophinia.pos.entity.ClaimStatus.open
                        || c.getStatus() == com.insophinia.pos.entity.ClaimStatus.in_progress)
                .count();
    }
}
