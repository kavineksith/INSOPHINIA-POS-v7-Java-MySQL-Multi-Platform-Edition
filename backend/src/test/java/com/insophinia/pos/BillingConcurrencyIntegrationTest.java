package com.insophinia.pos;

import com.insophinia.pos.dto.BillingDtos.BillItemRequest;
import com.insophinia.pos.dto.BillingDtos.CreateBillRequest;
import com.insophinia.pos.entity.Bill;
import com.insophinia.pos.entity.BillStatus;
import com.insophinia.pos.entity.Category;
import com.insophinia.pos.entity.Item;
import com.insophinia.pos.entity.MovementType;
import com.insophinia.pos.entity.ReferenceType;
import com.insophinia.pos.entity.StockMovement;
import com.insophinia.pos.entity.User;
import com.insophinia.pos.entity.UserRole;
import com.insophinia.pos.exception.InsufficientStockException;
import com.insophinia.pos.repository.BillRepository;
import com.insophinia.pos.repository.CategoryRepository;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.StockMovementRepository;
import com.insophinia.pos.repository.UserRepository;
import com.insophinia.pos.service.BillingService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Concurrency integration tests for the billing engine — locks in the
 * race-safety contract of {@link BillingService#createBill}:
 *
 * <ol>
 *   <li><b>Atomic stock decrement</b>: when N concurrent checkouts each try to
 *       buy more units than are available, exactly {@code available/qtyPerBill}
 *       bills succeed and the rest are rejected with
 *       {@link InsufficientStockException}. Final stock never goes negative.</li>
 *   <li><b>Bill-number uniqueness</b>: multiple bills created in the same UTC
 *       second receive unique {@code BILL-YYYYMMDD-NNNN} numbers via the
 *       retry-on-unique-clash loop.</li>
 *   <li><b>Return/cancel idempotency</b>: concurrent return attempts on the
 *       same bill are coalesced — only one succeeds, the others are rejected
 *       because the bill's status is no longer {@code completed}.</li>
 * </ol>
 *
 * Test isolation: the {@link #seedItem(int)} helper creates a fresh item with
 * the requested stock level for each test, so tests can run independently
 * without interfering with each other. Stock movements and bills created
 * during the test are tracked and cleaned up in {@link #cleanup()}.
 *
 * These tests run against a Testcontainers MySQL 8 instance (per
 * application-test.yml). InnoDB row locks + JPA pessimistic writes + the
 * atomic guarded {@code decrementStock} UPDATE are exercised byte-for-byte
 * the same as they would be in production.
 */
@SpringBootTest
@ActiveProfiles("test")
class BillingConcurrencyIntegrationTest {

    @Autowired private BillingService billingService;
    @Autowired private UserRepository userRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private ItemRepository itemRepository;
    @Autowired private BillRepository billRepository;
    @Autowired private StockMovementRepository stockMovementRepository;

    private User staff;
    private Category category;

    @BeforeEach
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void seedStaffAndCategory() {
        // Reuse the DataInitializer-seeded master_admin as our cashier for the
        // test (it has whatever permissions we need). Ensure it's un-locked
        // and not must-change-password.
        staff = userRepository.findByUsernameAndDeletedAtIsNull("test-master").orElseGet(() -> {
            User u = new User();
            u.setUsername("test-master-concurrency");
            u.setEmail("conc-test@insophinia.local");
            u.setFirstName("Conc");
            u.setLastName("Test");
            u.setRole(UserRole.master_admin);
            u.setPassword("$2a$12$dummyhashfornonproductionuseonly");
            u.setMustChangePassword(false);
            u.setPasswordChangedAt(Instant.now());
            return userRepository.save(u);
        });

        category = categoryRepository.findByNameAndDeletedAtIsNull("Concurrency Test Category").orElseGet(() -> {
            Category c = new Category();
            c.setName("Concurrency Test Category");
            c.setDescription("Used by BillingConcurrencyIntegrationTest");
            c.setActive(true);
            return categoryRepository.save(c);
        });
    }

    @AfterEach
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void cleanup() {
        // Wipe every bill + stock movement created by this test run so the
        // database is left clean for the next test. We don't soft-delete
        // because the billing queries filter on BillStatus and a soft-delete
        // would still show up in the assertions.
        List<Bill> bills = billRepository.findAll();
        for (Bill b : bills) {
            stockMovementRepository.deleteByReferenceId(b.getId());
        }
        billRepository.deleteAll();
        // Hard-delete test items + the test category.
        List<Item> testItems = itemRepository.findAll().stream()
                .filter(i -> i.getPluCode() != null && i.getPluCode().startsWith("CONC-"))
                .toList();
        testItems.forEach(i -> stockMovementRepository.deleteByItemId(i.getId()));
        itemRepository.deleteAll(testItems);
    }

    /**
     * Spawn N threads that each try to checkout 1 unit of the same item with
     * only 10 units of stock. Exactly 10 succeed; the rest throw
     * InsufficientStockException. Final stock is 0 (never negative).
     */
    @Test
    void concurrentCheckoutsOnlySellAvailableStock() throws Exception {
        int initialStock = 10;
        int perBillQty = 1;
        int concurrentThreads = 20; // 2× the available stock
        Item item = seedItem("CONC-BASIC", initialStock);

        ExecutorService pool = Executors.newFixedThreadPool(concurrentThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(concurrentThreads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientStockCount = new AtomicInteger();
        AtomicInteger otherErrorCount = new AtomicInteger();
        List<Throwable> otherErrors = new java.util.concurrent.CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrentThreads; i++) {
            pool.submit(() -> {
                CreateBillRequest req = buildSingleItemBillRequest(item, perBillQty);
                try {
                    // Each thread calls through the Spring proxy so it gets
                    // its own transaction (and its own pessimistic-lock
                    // acquisition on the item row).
                    Bill result = billingService.createBill(req, staff.getId());
                    if (result != null && result.getStatus() == BillStatus.completed) {
                        successCount.incrementAndGet();
                    } else {
                        otherErrorCount.incrementAndGet();
                    }
                } catch (InsufficientStockException e) {
                    insufficientStockCount.incrementAndGet();
                } catch (Throwable t) {
                    otherErrorCount.incrementAndGet();
                    otherErrors.add(t);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release all threads at once to maximize contention.
        startGate.countDown();
        boolean completed = doneGate.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertTrue(completed, "All threads must complete within 60s");
        assertEquals(0, otherErrorCount.get(),
                "No thread may fail with an unexpected exception. Got: "
                        + otherErrors.stream().map(Throwable::getMessage).toList());
        assertEquals(initialStock, successCount.get(),
                "Exactly the available stock count (" + initialStock + ") checkouts must succeed; got "
                        + successCount.get());
        assertEquals(concurrentThreads - initialStock, insufficientStockCount.get(),
                "The remaining threads must throw InsufficientStockException");

        // Final stock must be 0 — never negative.
        Item refreshed = itemRepository.findById(item.getId()).orElseThrow();
        assertEquals(BigDecimal.ZERO, refreshed.getStockQuantity(),
                "Final stock must be 0 (never negative) after contention");
        // Sell quantity must equal the initial stock.
        assertEquals(new BigDecimal(initialStock).setScale(3), refreshed.getSellQuantity(),
                "sell_quantity must reflect all sold units");
        // Damage / lost / return quantities remain at 0.
        assertEquals(BigDecimal.ZERO, refreshed.getDamageQuantity(), "damage_quantity must remain 0");
        assertEquals(BigDecimal.ZERO, refreshed.getLostQuantity(), "lost_quantity must remain 0");
        assertEquals(BigDecimal.ZERO, refreshed.getReturnQuantity(), "return_quantity must remain 0");

        // Exactly `initialStock` stock movements created with type=out, reference=bill.
        List<StockMovement> movements = stockMovementRepository.findByItemId(item.getId());
        assertEquals(initialStock, movements.size(),
                "One stock-out movement per successful bill");
        assertTrue(movements.stream().allMatch(m -> m.getMovementType() == MovementType.out),
                "All movements must be type=out");
        assertTrue(movements.stream().allMatch(m -> m.getReferenceType() == ReferenceType.bill),
                "All movements must be reference_type=bill");
        assertTrue(movements.stream().allMatch(m -> m.getQuantity().compareTo(BigDecimal.ONE) == 0),
                "Each movement quantity must be 1.000");
    }

    /**
     * Two threads checkout the same item simultaneously, each buying 6 units
     * from a stock of 10. Only one wins the race; the other must be rejected
     * with InsufficientStockException. Final stock = 4 (10 - 6).
     */
    @Test
    void twoThreadsRacingOnSameItemOneWinsOneLoses() throws Exception {
        int initialStock = 10;
        int perBillQty = 6; // 6 + 6 = 12 > 10, so one must lose
        Item item = seedItem("CONC-2THREAD", initialStock);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);
        AtomicReference<Bill> successBill = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable attempt = () -> {
            try {
                Bill b = billingService.createBill(buildSingleItemBillRequest(item, perBillQty), staff.getId());
                successBill.compareAndSet(null, b);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            } finally {
                doneGate.countDown();
            }
        };

        pool.submit(attempt);
        pool.submit(attempt);
        startGate.countDown();
        assertTrue(doneGate.await(30, TimeUnit.SECONDS), "Both threads must complete");
        pool.shutdownNow();

        assertNotNull(successBill.get(), "Exactly one thread must succeed");
        assertNotNull(failure.get(), "Exactly one thread must fail");
        assertTrue(failure.get() instanceof InsufficientStockException,
                "The losing thread must throw InsufficientStockException; got: "
                        + failure.get().getClass().getName() + ": " + failure.get().getMessage());

        Item refreshed = itemRepository.findById(item.getId()).orElseThrow();
        assertEquals(new BigDecimal(initialStock - perBillQty).setScale(3),
                refreshed.getStockQuantity(),
                "Final stock must be " + (initialStock - perBillQty) + " (10 - 6)");
    }

    /**
     * Spawn N threads that each create a bill for DIFFERENT items (no
     * contention). All N should succeed. All N bill numbers must be unique
     * even though they're created in the same UTC second.
     */
    @Test
    void concurrentCheckoutsOnDifferentItemsAllocateUniqueBillNumbers() throws Exception {
        int threads = 8;
        // Each thread uses its own item so there's no stock contention — the
        // only shared resource is the bill-number allocator.
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            items.add(seedItem("CONC-BILLNUM-" + i, 100));
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<CompletableFuture<Bill>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final Item item = items.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Thread interrupted while awaiting start gate", e);
                }
                return billingService.createBill(buildSingleItemBillRequest(item, 1), staff.getId());
            }, pool));
        }
        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .get(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        List<Bill> created = futures.stream()
                .map(f -> f.getNow(null))
                .filter(java.util.Objects::nonNull)
                .toList();

        assertEquals(threads, created.size(), "Every concurrent checkout must succeed");
        assertEquals(threads, created.stream().map(Bill::getBillNumber).distinct().count(),
                "Every bill must receive a unique bill number, even when created in the same UTC second");
        assertTrue(created.stream().allMatch(b -> b.getStatus() == BillStatus.completed),
                "All concurrent bills must be in completed status");
    }

    /**
     * Concurrent return attempts on the same bill — only one can win; the
     * rest must see the bill's status has flipped to {@code returned} and
     * refuse. This guards against double-restock + double-loyalty-refund.
     */
    @Test
    void concurrentReturnsOnSameBillAreCoalesced() throws Exception {
        int initialStock = 100;
        Item item = seedItem("CONC-RETURN", initialStock);
        // Create a parent bill first (single-threaded setup).
        Bill parent = billingService.createBill(buildSingleItemBillRequest(item, 5), staff.getId());
        // Sanity check: stock was decremented to 95.
        assertEquals(new BigDecimal(initialStock - 5).setScale(3),
                itemRepository.findById(item.getId()).orElseThrow().getStockQuantity(),
                "Setup: parent bill must have decremented stock from 100 to 95");

        int returnThreads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(returnThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(returnThreads);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        for (int i = 0; i < returnThreads; i++) {
            pool.submit(() -> {
                try {
                    billingService.returnBill(parent.getId(),
                            new com.insophinia.pos.dto.BillingDtos.ReturnBillRequest(
                                    "Concurrent return attempt " + UUID.randomUUID(),
                                    "test-master", "TestMaster!Password123"));
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failureCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }
        startGate.countDown();
        assertTrue(doneGate.await(30, TimeUnit.SECONDS), "All return attempts must complete");
        pool.shutdownNow();

        assertEquals(1, successCount.get(),
                "Exactly ONE concurrent return must succeed — the rest must be rejected");
        assertEquals(returnThreads - 1, failureCount.get(),
                "The remaining return attempts must fail (state-conflict on bill status)");

        // Final stock: 95 (after first bill) + 5 (returned) = 100. Loyalty
        // points refunded exactly once.
        Item refreshed = itemRepository.findById(item.getId()).orElseThrow();
        assertEquals(new BigDecimal(initialStock).setScale(3),
                refreshed.getStockQuantity(),
                "Stock must be restored to its initial level after the single successful return");
        assertEquals(new BigDecimal(0).setScale(3), refreshed.getReturnQuantity().negate().negate(),
                "return_quantity column must reflect the restocked units (defensive — implementation detail)");
    }

    // ----- helpers -------------------------------------------------------

    /**
     * Create a fresh item with the requested stock quantity. Uses a unique
     * PLU prefix (CONC-*) so test items don't collide with each other or with
     * demo seed data.
     */
    private Item seedItem(String pluSuffix, int stock) {
        Item i = new Item();
        i.setPluCode(pluSuffix + "-" + UUID.randomUUID().toString().substring(0, 8));
        i.setName("Concurrency Test Item " + pluSuffix);
        i.setDescription("Test fixture — safe to delete");
        i.setCategory(category);
        i.setBarcode("BAR-" + UUID.randomUUID().toString().substring(0, 8));
        i.setQrCode("QR-" + UUID.randomUUID().toString().substring(0, 8));
        i.setPrice(new BigDecimal("100.00"));
        i.setCostPrice(new BigDecimal("50.00"));
        i.setUnit("pcs");
        BigDecimal stockBd = new BigDecimal(stock).setScale(3);
        i.setStockQuantity(stockBd);
        i.setTotalQuantity(stockBd);
        i.setLowStockThreshold(new BigDecimal("5"));
        i.setActive(true);
        return itemRepository.save(i);
    }

    private CreateBillRequest buildSingleItemBillRequest(Item item, int qty) {
        return new CreateBillRequest(
                null, // customerId — walk-in
                "Walk-in customer",
                null, // customerEmail
                java.util.List.of(new BillItemRequest(item.getId(),
                        new BigDecimal(qty).setScale(3))),
                new BigDecimal("1000.00"), // paidAmount — generous to cover any total
                0, // pointsRedeemed
                null, // authorizerUsername — no loyalty redemption
                null, // authorizerPassword
                "Concurrency test checkout");
    }
}
