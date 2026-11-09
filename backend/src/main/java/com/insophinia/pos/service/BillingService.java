package com.insophinia.pos.service;

import com.insophinia.pos.dto.BillingDtos.BillItemResponse;
import com.insophinia.pos.dto.BillingDtos.BillResponse;
import com.insophinia.pos.dto.BillingDtos.CreateBillRequest;
import com.insophinia.pos.dto.BillingDtos.ReturnBillRequest;
import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillItem;
import com.insophinia.pos.entity.BillStatus;
import com.insophinia.pos.entity.Customer;
import com.insophinia.pos.entity.DiscountType;
import com.insophinia.pos.entity.Item;
import com.insophinia.pos.entity.MovementType;
import com.insophinia.pos.entity.Promotion;
import com.insophinia.pos.entity.ReferenceType;
import com.insophinia.pos.entity.StockMovement;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ForbiddenException;
import com.insophinia.pos.exception.InsufficientStockException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.exception.UnauthorizedException;
import com.insophinia.pos.repository.BillRepository;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.PromotionRepository;
import com.insophinia.pos.repository.StockMovementRepository;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.security.Permissions;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Billing engine — port of src/app/api/billing/*.
 * Preserves: active-promotion resolution (item promo beats category promo),
 * per-line discount, VAT/SSCL computed from settings, loyalty earn/redeem with
 * supervisor authorization, atomic guarded stock decrement (race-safe across
 * terminals), bill-number retry on unique clash.
 */
@Service
public class BillingService {

    private static final int MAX_BILL_NUMBER_RETRIES = 3;

    private final BillRepository bills;
    private final ItemRepository items;
    private final CustomerRepository customers;
    private final PromotionRepository promotions;
    private final UserRepository users;
    private final StockMovementRepository movements;
    private final SettingsService settings;
    private final PasswordEncoder passwordEncoder;
    private final SyncHub syncHub;
    private final SystemLogService systemLog;

    public BillingService(BillRepository bills, ItemRepository items, CustomerRepository customers,
                          PromotionRepository promotions, UserRepository users,
                          StockMovementRepository movements, SettingsService settings,
                          PasswordEncoder passwordEncoder, SyncHub syncHub, SystemLogService systemLog) {
        this.bills = bills;
        this.items = items;
        this.customers = customers;
        this.promotions = promotions;
        this.users = users;
        this.movements = movements;
        this.settings = settings;
        this.passwordEncoder = passwordEncoder;
        this.syncHub = syncHub;
        this.systemLog = systemLog;
    }

    @Transactional(readOnly = true)
    public Page<Bill> listBills(String status, Instant from, Instant to, String search, Pageable pageable) {
        Specification<Bill> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (status != null && !status.isBlank()) {
            BillStatus st;
            try { st = BillStatus.valueOf(status); }
            catch (IllegalArgumentException e) { throw new BadRequestException("Invalid status filter"); }
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), st));
        }
        if (from != null) spec = spec.and((root, q, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from));
        if (to != null) spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("createdAt"), to));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.toLowerCase() + "%";
            spec = spec.and((root, q, cb) -> {
                var customer = root.join("customer", jakarta.persistence.criteria.JoinType.LEFT);
                return cb.or(cb.like(cb.lower(root.get("billNumber")), like),
                        cb.like(cb.lower(root.get("customerName")), like),
                        cb.like(cb.lower(root.get("customerEmail")), like),
                        cb.like(cb.lower(customer.get("phone")), like),
                        cb.like(cb.lower(customer.get("email")), like));
            });
        }
        return bills.findAll(spec, pageable);
    }

    @Transactional(readOnly = true)
    public Bill getBill(UUID id) {
        return bills.findDetailedById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill not found"));
    }

    @Transactional
    public Bill createBill(CreateBillRequest req, UUID staffUserId) {
        User staff = users.findByIdAndDeletedAtIsNull(staffUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found"));
        Instant now = Instant.now();

        // Load items with pessimistic lock to serialize concurrent checkouts on hot items
        Map<UUID, Item> itemMap = new HashMap<>();
        for (var line : req.items()) {
            Item item = items.findByIdForUpdate(line.itemId())
                    .filter(i -> i.getDeletedAt() == null && i.isActive())
                    .orElseThrow(() -> new ResourceNotFoundException("One or more items not found or inactive"));
            if (item.getStockQuantity().compareTo(line.quantity()) < 0) {
                throw new InsufficientStockException("Insufficient stock for " + item.getName()
                        + ". Available: " + item.getStockQuantity());
            }
            itemMap.put(line.itemId(), item);
        }

        List<Promotion> activePromotions = promotions.findActiveAt(now);
        int loyaltyPointValue = settings.getInt(SettingsService.KEY_LOYALTY_POINT_VALUE, 100);

        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal totalDiscount = BigDecimal.ZERO;
        var lineData = new java.util.ArrayList<BillItem>();

        for (var line : req.items()) {
            Item item = itemMap.get(line.itemId());
            BigDecimal unitPrice = item.getPrice();
            BigDecimal discountPct = item.isHasDiscount() ? item.getDiscountPercentage() : BigDecimal.ZERO;

            Promotion promo = activePromotions.stream()
                    .filter(p -> line.quantity().compareTo(java.math.BigDecimal.valueOf(p.getMinItemCount())) >= 0)
                    .filter(p -> p.getItem() != null && p.getItem().getId().equals(item.getId()))
                    .findFirst()
                    .or(() -> activePromotions.stream()
                            .filter(p -> line.quantity().compareTo(java.math.BigDecimal.valueOf(p.getMinItemCount())) >= 0)
                            .filter(p -> p.getCategory() != null && p.getCategory().getId().equals(item.getCategory().getId()))
                            .findFirst())
                    .orElse(null);

            if (promo != null && promo.getDiscountType() == DiscountType.percentage) {
                discountPct = discountPct.max(promo.getDiscountValue());
            }

            BigDecimal discountAmt = unitPrice.multiply(discountPct)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal actualPrice = unitPrice.subtract(discountAmt);
            BigDecimal lineSubtotal = actualPrice.multiply(line.quantity()).setScale(2, RoundingMode.HALF_UP);

            subtotal = subtotal.add(unitPrice.multiply(line.quantity()));
            totalDiscount = totalDiscount.add(discountAmt.multiply(line.quantity()));

            BillItem bi = new BillItem();
            bi.setItem(item);
            bi.setPluCode(item.getPluCode());
            bi.setItemName(item.getName());
            bi.setQuantity(line.quantity());
            bi.setUnit(item.getUnit());
            bi.setUnitPrice(unitPrice);
            bi.setActualPrice(actualPrice);
            bi.setDiscount(discountAmt);
            bi.setDiscountPercentage(discountPct);
            bi.setSubtotal(lineSubtotal);
            lineData.add(bi);
        }

        BigDecimal net = subtotal.subtract(totalDiscount);

        // Taxes from settings (VAT / SSCL — Sri Lankan retail context, same as reference app)
        boolean vatEnabled = settings.getBool(SettingsService.KEY_VAT_ENABLED, false);
        boolean ssclEnabled = settings.getBool(SettingsService.KEY_SSCL_ENABLED, false);
        BigDecimal vatRate = BigDecimal.valueOf(settings.getDouble(SettingsService.KEY_VAT_RATE, 18));
        BigDecimal ssclRate = BigDecimal.valueOf(settings.getDouble(SettingsService.KEY_SSCL_RATE, 2.5));
        BigDecimal sscl = ssclEnabled
                ? net.multiply(ssclRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal vat = vatEnabled
                ? net.add(sscl).multiply(vatRate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        BigDecimal taxTotal = sscl.add(vat);

        BigDecimal totalAmount = net.add(taxTotal);

        // Loyalty redemption with supervisor authorization
        int pointsRedeemed = req.pointsRedeemed() == null ? 0 : req.pointsRedeemed();
        BigDecimal pointsValue = BigDecimal.ZERO;
        String authorizerName = null;
        Instant pointsAuthorizedAt = null;

        if (pointsRedeemed > 0) {
            if (req.customerId() == null) {
                throw new BadRequestException("Customer selection required for point redemption");
            }
            Customer customer = customers.findByIdAndDeletedAtIsNull(req.customerId())
                    .orElseThrow(() -> new BadRequestException("Customer not found"));
            if (customer.getLoyaltyPoints() < pointsRedeemed) {
                throw new BadRequestException("Insufficient loyalty points");
            }
            if (pointsRedeemed < 100) {
                throw new BadRequestException("Minimum 100 points required for redemption");
            }
            if (req.authorizerUsername() == null || req.authorizerPassword() == null) {
                throw new UnauthorizedException("Supervisor authorization required for point redemption");
            }
            User authorizer = users.findByUsernameAndDeletedAtIsNull(req.authorizerUsername())
                    .filter(User::isActive)
                    .orElseThrow(() -> new UnauthorizedException("Authorizer not found or inactive"));
            if (!Permissions.has(authorizer.getRole(), Permissions.AUTHORIZE_LOYALTY)) {
                throw new ForbiddenException("Authorizer does not have permission for point redemption");
            }
            if (!passwordEncoder.matches(req.authorizerPassword(), authorizer.getPassword())) {
                throw new UnauthorizedException("Invalid authorizer password");
            }
            authorizerName = authorizer.getFirstName() + " " + authorizer.getLastName();
            pointsAuthorizedAt = now;
            pointsValue = BigDecimal.valueOf(pointsRedeemed); // 1 point = Rs. 1.00
            totalAmount = totalAmount.subtract(pointsValue).max(BigDecimal.ZERO);
        }

        BigDecimal balance = req.paidAmount().subtract(totalAmount);
        int earnedPoints = totalAmount.divide(BigDecimal.valueOf(Math.max(loyaltyPointValue, 1)),
                0, RoundingMode.FLOOR).intValue();

        Customer customer = null;
        if (req.customerId() != null) {
            customer = customers.findByIdAndDeletedAtIsNull(req.customerId())
                    .orElseThrow(() -> new BadRequestException("Customer not found"));
        }

        // Bill-number generation with retry on unique clash (two terminals, same second).
        String billNumber = null;
        for (int attempt = 0; attempt < MAX_BILL_NUMBER_RETRIES; attempt++) {
            String today = LocalDate.now(ZoneOffset.UTC).toString().replace("-", "");
            long count = bills.countByCreatedAtGreaterThanEqual(LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC));
            String candidate = "BILL-%s-%04d".formatted(today, count + 1 + attempt);
            if (!bills.existsByBillNumber(candidate)) { billNumber = candidate; break; }
        }
        if (billNumber == null) {
            throw new com.insophinia.pos.exception.ConflictException("Failed to allocate a bill number after retries");
        }
        try {
            return persistBill(req, staff, now, lineData, subtotal, totalDiscount, vat, sscl, taxTotal,
                    totalAmount, balance, earnedPoints, pointsRedeemed, pointsValue,
                    authorizerName, pointsAuthorizedAt, customer, billNumber);
        } catch (DataIntegrityViolationException e) {
            // Another terminal grabbed the same number between our check and the flush.
            throw new com.insophinia.pos.exception.ConflictException(
                    "Bill number clash with a concurrent sale — please retry the checkout");
        }
    }

    private Bill persistBill(CreateBillRequest req, User staff, Instant now, List<BillItem> lineData,
                               BigDecimal subtotal, BigDecimal totalDiscount, BigDecimal vat, BigDecimal sscl,
                               BigDecimal taxTotal, BigDecimal totalAmount, BigDecimal balance,
                               int earnedPoints, int pointsRedeemed, BigDecimal pointsValue,
                               String authorizerName, Instant pointsAuthorizedAt,
                               Customer customer, String billNumber) {
        Bill bill = new Bill();
        bill.setBillNumber(billNumber);
        bill.setCustomer(customer);
        bill.setCustomerName(req.customerName() == null ? "Customer" : req.customerName());
        bill.setCustomerEmail(req.customerEmail());
        bill.setStaff(staff);
        bill.setStaffName(staff.getFirstName() + " " + staff.getLastName());
        bill.setStartTime(now);
        bill.setEndTime(now);
        bill.setSubtotal(subtotal.setScale(2, RoundingMode.HALF_UP));
        bill.setTotalDiscount(totalDiscount.setScale(2, RoundingMode.HALF_UP));
        bill.setVatAmount(vat);
        bill.setSsclAmount(sscl);
        bill.setTaxTotal(taxTotal);
        bill.setTotalAmount(totalAmount.setScale(2, RoundingMode.HALF_UP));
        bill.setPaidAmount(req.paidAmount());
        bill.setBalance(balance.setScale(2, RoundingMode.HALF_UP));
        bill.setStatus(BillStatus.completed);
        bill.setRemarks(req.remarks());
        bill.setEarnedPoints(earnedPoints);
        bill.setPointsRedeemed(pointsRedeemed);
        bill.setPointsValue(pointsValue);
        bill.setPointsAuthorizedBy(authorizerName);
        bill.setPointsAuthorizedAt(pointsAuthorizedAt);

        if (customer != null) {
            customer.setLoyaltyPoints(customer.getLoyaltyPoints() + earnedPoints - pointsRedeemed);
            customers.save(customer);
            bill.setTotalPointsAfter(customer.getLoyaltyPoints());
        }

        for (BillItem bi : lineData) {
            bi.setBill(bill);
            bill.getBillItems().add(bi);
        }
        Bill saved = bills.saveAndFlush(bill); // flush here so a billNumber clash surfaces for retry

        for (BillItem bi : lineData) {
            // Atomic guarded decrement — race-safe across terminals (port of updateMany WHERE stock>=qty)
            int updated = items.decrementStock(bi.getItem().getId(), bi.getQuantity(), Instant.now());
            if (updated == 0) {
                throw new InsufficientStockException("Insufficient stock for " + bi.getItemName()
                        + " — another sale just used the remaining units. Please refresh and try again.");
            }
            StockMovement movement = new StockMovement();
            movement.setItem(bi.getItem());
            movement.setMovementType(MovementType.out);
            movement.setQuantity(bi.getQuantity());
            movement.setReferenceType(ReferenceType.bill);
            movement.setReferenceId(saved.getId());
            movement.setNotes("Sold via bill " + billNumber);
            movement.setMovementDate(now);
            movement.setCreatedBy(staff.getId());
            movements.save(movement);
        }

        // Realtime sync broadcast to other terminals
        syncHub.broadcast("BILL_CREATED", "bill",
                Map.of("billId", saved.getId().toString(), "billNumber", billNumber));
        if (customer != null) {
            syncHub.broadcast("CUSTOMER_UPDATED", "customer", Map.of("customerId", customer.getId().toString()));
        }
        for (BillItem bi : lineData) {
            syncHub.broadcast("STOCK_UPDATED", "item",
                    Map.of("itemId", bi.getItem().getId().toString(), "itemName", bi.getItemName()));
        }

        systemLog.append("info", "billing", "BILL_CREATE",
                "Created bill " + billNumber + " for " + saved.getTotalAmount(), null, staff.getId(), null);
        return saved;
    }

    @Transactional
    public Bill returnBill(UUID billId, ReturnBillRequest req) {
        Bill bill = getBill(billId);
        if (bill.getStatus() != BillStatus.completed) {
            throw new BadRequestException("Only completed bills can be returned");
        }
        User authorizer = users.findByUsernameAndDeletedAtIsNull(req.authorizerUsername())
                .filter(User::isActive)
                .orElseThrow(() -> new UnauthorizedException("Authorizer not found or inactive"));
        if (!Permissions.has(authorizer.getRole(), Permissions.AUTHORIZE_RETURNS)) {
            throw new ForbiddenException("Authorizer does not have permission to authorize returns");
        }
        if (!passwordEncoder.matches(req.authorizerPassword(), authorizer.getPassword())) {
            throw new UnauthorizedException("Invalid authorizer password");
        }

        bill.setStatus(BillStatus.returned);
        bill.setReturnReason(req.reason());
        bill.setReturnAuthorizedBy(authorizer.getId());
        bill.setReturnAuthorizedAt(Instant.now());

        // Restock every line
        for (BillItem bi : bill.getBillItems()) {
            items.incrementStockForReturn(bi.getItem().getId(), bi.getQuantity(), Instant.now());
            StockMovement movement = new StockMovement();
            movement.setItem(bi.getItem());
            movement.setMovementType(MovementType.return_item);
            movement.setQuantity(bi.getQuantity());
            movement.setReferenceType(ReferenceType.bill);
            movement.setReferenceId(bill.getId());
            movement.setNotes("Return of bill " + bill.getBillNumber() + ": " + req.reason());
            movement.setMovementDate(Instant.now());
            movement.setCreatedBy(authorizer.getId());
            movements.save(movement);
        }

        // Reverse loyalty points
        Customer customer = bill.getCustomer();
        if (customer != null) {
            customer.setLoyaltyPoints(Math.max(0,
                    customer.getLoyaltyPoints() - bill.getEarnedPoints() + bill.getPointsRedeemed()));
            customers.save(customer);
        }

        bills.save(bill);
        syncHub.broadcast("BILL_RETURNED", "bill",
                Map.of("billId", bill.getId().toString(), "billNumber", bill.getBillNumber()));
        return bill;
    }

    @Transactional
    public Bill cancelBill(UUID billId, String reason, String authorizerUsername, String authorizerPassword) {
        Bill bill = getBill(billId);
        if (bill.getStatus() == BillStatus.returned) {
            throw new BadRequestException("Returned bills cannot be cancelled");
        }
        if (bill.getStatus() == BillStatus.cancelled) {
            throw new BadRequestException("Bill is already cancelled");
        }
        if (bill.getStatus() != BillStatus.completed) {
            throw new BadRequestException("Only completed bills can be cancelled");
        }
        if (authorizerUsername == null || authorizerPassword == null) {
            throw new BadRequestException("Supervisor credentials are required to cancel a bill");
        }
        User authorizer = users.findByUsernameAndDeletedAtIsNull(authorizerUsername)
                .orElseThrow(() -> new UnauthorizedException("Invalid supervisor credentials"));
        if (!Permissions.has(authorizer.getRole(), Permissions.AUTHORIZE_RETURNS)
                || !authorizer.isActive() || !passwordEncoder.matches(authorizerPassword, authorizer.getPassword())) {
            throw new UnauthorizedException("Supervisor authorization failed");
        }
        Instant now = Instant.now();
        for (BillItem line : bill.getBillItems()) {
            Item item = items.findByIdForUpdate(line.getItem().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Item not found while restoring stock"));
            item.setStockQuantity(item.getStockQuantity().add(line.getQuantity()));
            item.setSellQuantity(item.getSellQuantity().subtract(line.getQuantity()).max(BigDecimal.ZERO));
            items.save(item);
            StockMovement movement = new StockMovement();
            movement.setItem(item);
            movement.setMovementType(MovementType.in);
            movement.setQuantity(line.getQuantity());
            movement.setReferenceType(ReferenceType.bill);
            movement.setReferenceId(bill.getId());
            movement.setNotes("Stock restored after cancellation of bill " + bill.getBillNumber());
            movement.setMovementDate(now);
            movement.setCreatedBy(authorizer.getId());
            movements.save(movement);
        }
        Customer customer = bill.getCustomer();
        if (customer != null) {
            customer.setLoyaltyPoints(Math.max(0,
                    customer.getLoyaltyPoints() - bill.getEarnedPoints() + bill.getPointsRedeemed()));
            customers.save(customer);
        }
        bill.setStatus(BillStatus.cancelled);
        String auditReason = (reason == null || reason.isBlank()) ? "No reason supplied" : reason.trim();
        bill.setRemarks((bill.getRemarks() == null ? "" : bill.getRemarks() + " | ")
                + "Cancelled by supervisor " + authorizer.getUsername() + ": " + auditReason);
        bills.save(bill);
        systemLog.append("WARN", "BILLING", "BILL_CANCEL", "Bill " + bill.getBillNumber() + " cancelled by " + authorizer.getUsername(),
                "billId=" + bill.getId() + "; reason=" + auditReason, authorizer.getId(), null);
        syncHub.broadcast("BILL_CANCELLED", "bill",
                Map.of("billId", bill.getId().toString(), "billNumber", bill.getBillNumber()));
        return bill;
    }

    @Transactional
    public Bill markEmailSent(UUID billId) {
        Bill bill = getBill(billId);
        bill.setEmailSent(true);
        return bills.save(bill);
    }

    @Transactional
    public Bill markPrinted(UUID billId) {
        Bill bill = getBill(billId);
        bill.setPrinted(true);
        return bills.save(bill);
    }

    public static BillResponse toResponse(Bill b) {
        return new BillResponse(b.getId(), b.getBillNumber(),
                b.getCustomer() == null ? null : b.getCustomer().getId(),
                b.getCustomerName(), b.getCustomerEmail(), b.getStaffName(),
                b.getStartTime(), b.getEndTime(),
                b.getSubtotal(), b.getTotalDiscount(), b.getVatAmount(), b.getSsclAmount(), b.getTaxTotal(),
                b.getTotalAmount(), b.getPaidAmount(), b.getBalance(),
                b.getStatus().name(), b.getReturnReason(), b.getRemarks(),
                b.isPrinted(), b.isEmailSent(),
                b.getEarnedPoints(), b.getPointsRedeemed(), b.getPointsValue(),
                b.getPointsAuthorizedBy(), b.getTotalPointsAfter(),
                b.getCreatedAt(),
                b.getBillItems() == null ? List.of() : b.getBillItems().stream().map(bi ->
                        new BillItemResponse(bi.getId(), bi.getItem().getId(), bi.getPluCode(), bi.getItemName(),
                                bi.getQuantity(), bi.getUnit(), bi.getUnitPrice(), bi.getActualPrice(),
                                bi.getDiscount(), bi.getDiscountPercentage(), bi.getSubtotal())).toList());
    }
}
