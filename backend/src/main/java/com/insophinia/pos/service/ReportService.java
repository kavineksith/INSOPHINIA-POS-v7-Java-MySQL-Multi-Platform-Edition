package com.insophinia.pos.service;

import com.insophinia.pos.dto.MiscDtos.DashboardResponse;
import com.insophinia.pos.entity.BillStatus;
import com.insophinia.pos.repository.BillItemRepository;
import com.insophinia.pos.repository.BillRepository;
import com.insophinia.pos.repository.CustomerRepository;
import com.insophinia.pos.repository.ItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ReportService {

    private final BillRepository bills;
    private final BillItemRepository billItems;
    private final ItemRepository items;
    private final CustomerRepository customers;

    @PersistenceContext
    private EntityManager em;

    public ReportService(BillRepository bills, BillItemRepository billItems,
                         ItemRepository items, CustomerRepository customers) {
        this.bills = bills;
        this.billItems = billItems;
        this.items = items;
        this.customers = customers;
    }

    public DashboardResponse dashboard() {
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant startOfMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);

        BigDecimal todaySales = sumCompleted(startOfToday, Instant.now());
        long todayBills = countCompleted(startOfToday, Instant.now());
        BigDecimal monthSales = sumCompleted(startOfMonth, Instant.now());

        List<Map<String, Object>> last7 = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(i);
            Instant from = day.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            Map<String, Object> row = new HashMap<>();
            row.put("date", day.toString());
            row.put("revenue", sumCompleted(from, to));
            row.put("bills", countCompleted(from, to));
            last7.add(row);
        }

        List<Map<String, Object>> top = new ArrayList<>();
        billItems.topItemsBetween(Instant.now().minus(30, ChronoUnit.DAYS), Instant.now(), org.springframework.data.domain.PageRequest.of(0, 5)).stream()
                .forEach(r -> {
                    Map<String, Object> row = new HashMap<>();
                    row.put("itemId", r.getItemId());
                    row.put("pluCode", r.getPluCode());
                    row.put("itemName", r.getItemName());
                    row.put("totalQty", r.getTotalQty());
                    row.put("totalRevenue", r.getTotalRevenue());
                    top.add(row);
                });

        long lowStock = items.countLowStockLive();

        return new DashboardResponse(todaySales, todayBills, items.countByDeletedAtIsNull(), lowStock,
                customers.countByDeletedAtIsNull(), customers.sumLoyaltyPoints(), monthSales, last7, top);
    }

    public List<Object[]> salesReport(Instant from, Instant to) {
        return em.createQuery(
                "select function('date', b.createdAt), count(b), sum(b.totalAmount), sum(b.totalDiscount) " +
                "from Bill b where b.deletedAt is null and b.status = :status and b.createdAt between :from and :to " +
                "group by function('date', b.createdAt) order by function('date', b.createdAt)", Object[].class)
                .setParameter("status", BillStatus.completed)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();
    }

    public List<BillItemRepository.ItemSalesRow> itemsReport(Instant from, Instant to) {
        return billItems.itemSalesBetween(from, to);
    }

    private BigDecimal sumCompleted(Instant from, Instant to) {
        BigDecimal result = em.createQuery(
                "select coalesce(sum(b.totalAmount), 0) from Bill b " +
                "where b.deletedAt is null and b.status = :status and b.createdAt between :from and :to",
                BigDecimal.class)
                .setParameter("status", BillStatus.completed)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return result == null ? BigDecimal.ZERO : result;
    }

    private long countCompleted(Instant from, Instant to) {
        Long result = em.createQuery(
                "select count(b) from Bill b " +
                "where b.deletedAt is null and b.status = :status and b.createdAt between :from and :to",
                Long.class)
                .setParameter("status", BillStatus.completed)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();
        return result == null ? 0 : result;
    }
}
