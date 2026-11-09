package com.insophinia.pos.service;

import com.insophinia.pos.dto.InventoryDtos.LowStockItem;
import com.insophinia.pos.dto.InventoryDtos.StockAdjustmentRequest;
import com.insophinia.pos.dto.InventoryDtos.StockMovementResponse;
import com.insophinia.pos.entity.Item;
import com.insophinia.pos.entity.MovementType;
import com.insophinia.pos.entity.ReferenceType;
import com.insophinia.pos.entity.StockMovement;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.StockMovementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class InventoryService {

    private final ItemRepository items;
    private final StockMovementRepository movements;
    private final SyncHub syncHub;

    public InventoryService(ItemRepository items, StockMovementRepository movements, SyncHub syncHub) {
        this.items = items;
        this.movements = movements;
        this.syncHub = syncHub;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Page<StockMovement> listMovements(UUID itemId, Pageable pageable) {
        if (itemId != null) {
            return movements.findByItemIdOrderByMovementDateDesc(itemId, pageable);
        }
        return movements.findAll(pageable);
    }

    public List<LowStockItem> lowStock() {
        return items.findByDeletedAtIsNullAndActiveTrue().stream()
                .filter(i -> i.getStockQuantity().compareTo(i.getLowStockThreshold()) <= 0)
                .map(i -> new LowStockItem(i.getId(), i.getPluCode(), i.getName(),
                        i.getStockQuantity(), i.getLowStockThreshold()))
                .toList();
    }

    @Transactional
    public StockMovementResponse stockIn(StockAdjustmentRequest req, UUID userId) {
        return adjust(req, userId, MovementType.in);
    }

    @Transactional
    public StockMovementResponse stockOut(StockAdjustmentRequest req, UUID userId) {
        return adjust(req, userId, MovementType.out);
    }

    @Transactional
    public StockMovementResponse damage(StockAdjustmentRequest req, UUID userId) {
        return adjust(req, userId, MovementType.damage);
    }

    @Transactional
    public StockMovementResponse lost(StockAdjustmentRequest req, UUID userId) {
        return adjust(req, userId, MovementType.lost);
    }

    private StockMovementResponse adjust(StockAdjustmentRequest req, UUID userId, MovementType type) {
        Item item = items.findByIdForUpdate(req.itemId())
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));

        BigDecimal qty = req.quantity();
        switch (type) {
            case in -> {
                item.setStockQuantity(item.getStockQuantity().add(qty));
                item.setTotalQuantity(item.getTotalQuantity().add(qty));
            }
            case out, damage, lost -> {
                if (item.getStockQuantity().compareTo(qty) < 0) {
                    throw new BadRequestException("Insufficient stock for " + item.getName()
                            + ". Available: " + item.getStockQuantity());
                }
                item.setStockQuantity(item.getStockQuantity().subtract(qty));
                switch (type) {
                    case damage -> item.setDamageQuantity(item.getDamageQuantity().add(qty));
                    case lost -> item.setLostQuantity(item.getLostQuantity().add(qty));
                    default -> { /* manual out only reduces stock */ }
                }
            }
            default -> throw new BadRequestException("Unsupported movement type");
        }
        items.save(item);

        StockMovement m = new StockMovement();
        m.setItem(item);
        m.setMovementType(type);
        m.setQuantity(qty);
        m.setReferenceType(ReferenceType.manual);
        m.setNotes(req.notes());
        m.setMovementDate(Instant.now());
        m.setCreatedBy(userId);
        movements.save(m);

        syncHub.broadcast("STOCK_UPDATED", "item",
                Map.of("itemId", item.getId().toString(), "itemName", item.getName()));
        return toResponse(m);
    }

    public static StockMovementResponse toResponse(StockMovement m) {
        return new StockMovementResponse(m.getId(), m.getItem().getId(), m.getItem().getName(),
                m.getMovementType().name(), m.getQuantity(),
                m.getReferenceType() == null ? null : m.getReferenceType().name(),
                m.getReferenceId(), m.getNotes(), m.getMovementDate(), m.getCreatedBy());
    }
}
