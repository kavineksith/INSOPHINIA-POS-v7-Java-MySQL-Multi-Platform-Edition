package com.insophinia.pos.repository;

import com.insophinia.pos.entity.BillItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BillItemRepository extends JpaRepository<BillItem, UUID> {

    interface ItemSalesRow {
        UUID getItemId();
        String getPluCode();
        String getItemName();
        java.math.BigDecimal getTotalQty();
        java.math.BigDecimal getTotalRevenue();
    }

    @Query("select bi.item.id as itemId, bi.pluCode as pluCode, bi.itemName as itemName, "
            + "sum(bi.quantity) as totalQty, sum(bi.subtotal) as totalRevenue "
            + "from BillItem bi where bi.bill.status = com.insophinia.pos.entity.BillStatus.completed "
            + "and bi.bill.deletedAt is null and bi.bill.createdAt between :from and :to "
            + "group by bi.item.id, bi.pluCode, bi.itemName order by totalRevenue desc")
    List<ItemSalesRow> itemSalesBetween(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select bi.item.id as itemId, bi.pluCode as pluCode, bi.itemName as itemName, sum(bi.quantity) as totalQty, sum(bi.subtotal) as totalRevenue from BillItem bi where bi.bill.status = com.insophinia.pos.entity.BillStatus.completed and bi.bill.deletedAt is null and bi.bill.createdAt between :from and :to group by bi.item.id, bi.pluCode, bi.itemName order by totalRevenue desc")
    List<ItemSalesRow> topItemsBetween(@Param("from") Instant from, @Param("to") Instant to, org.springframework.data.domain.Pageable pageable);
}
