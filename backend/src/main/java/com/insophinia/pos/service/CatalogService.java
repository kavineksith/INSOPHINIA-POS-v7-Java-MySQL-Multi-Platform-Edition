package com.insophinia.pos.service;

import com.insophinia.pos.dto.CatalogDtos.CategoryRequest;
import com.insophinia.pos.dto.CatalogDtos.CategoryResponse;
import com.insophinia.pos.dto.CatalogDtos.ItemRequest;
import com.insophinia.pos.dto.CatalogDtos.ItemResponse;
import com.insophinia.pos.dto.CatalogDtos.PromotionRequest;
import com.insophinia.pos.dto.CatalogDtos.PromotionResponse;
import com.insophinia.pos.entity.Category;
import com.insophinia.pos.entity.DiscountType;
import com.insophinia.pos.entity.Item;
import com.insophinia.pos.entity.Promotion;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ConflictException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.CategoryRepository;
import com.insophinia.pos.repository.ItemRepository;
import com.insophinia.pos.repository.PromotionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class CatalogService {

    private final CategoryRepository categories;
    private final ItemRepository items;
    private final PromotionRepository promotions;
    private final SyncHub syncHub;

    public CatalogService(CategoryRepository categories, ItemRepository items,
                          PromotionRepository promotions, SyncHub syncHub) {
        this.categories = categories;
        this.items = items;
        this.promotions = promotions;
        this.syncHub = syncHub;
    }

    // ---------- categories ----------
    @Transactional(readOnly = true)
    public Page<Category> listCategories(String search, Boolean includeDeleted, Boolean deletedOnly, Pageable pageable) {
        return (search == null || search.isBlank())
                ? categories.findByDeletedAtIsNull(pageable)
                : categories.findByDeletedAtIsNullAndNameContainingIgnoreCase(search, pageable);
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest req) {
        if (categories.existsByNameIgnoreCaseAndDeletedAtIsNull(req.name())) {
            throw new ConflictException("A category with this name already exists");
        }
        Category c = new Category();
        c.setName(req.name());
        c.setDescription(req.description());
        c.setActive(req.active() == null || req.active());
        categories.save(c);
        syncHub.broadcast("CATEGORY_CREATED", "category", Map.of("categoryId", c.getId().toString()));
        return toCategoryResponse(c);
    }

    @Transactional
    public CategoryResponse updateCategory(UUID id, CategoryRequest req) {
        Category c = categories.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        c.setName(req.name());
        c.setDescription(req.description());
        if (req.active() != null) c.setActive(req.active());
        categories.save(c);
        syncHub.broadcast("CATEGORY_UPDATED", "category", Map.of("categoryId", id.toString()));
        return toCategoryResponse(c);
    }

    @Transactional
    public void deleteCategory(UUID id, boolean hard) {
        if (hard) {
            categories.findById(id).ifPresent(c -> {
                categories.delete(c);
                syncHub.broadcast("CATEGORY_HARD_DELETED", "category", Map.of("categoryId", id.toString()));
            });
            return;
        }
        Category c = categories.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        c.setDeletedAt(Instant.now()); // soft delete
        categories.save(c);
        syncHub.broadcast("CATEGORY_DELETED", "category", Map.of("categoryId", id.toString()));
    }

    @Transactional
    public Category restoreCategory(UUID id) {
        Category c = categories.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
        c.setDeletedAt(null);
        return categories.save(c);
    }


    @Transactional(readOnly = true)
    public Category getCategory(UUID id) {
        return categories.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found"));
    }

    @Transactional(readOnly = true)
    public Promotion getPromotion(UUID id) {
        return promotions.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
    }

    // ---------- items ----------
    @Transactional(readOnly = true)
    public Page<Item> listItems(String search, String sort, String direction, Boolean includeDeleted, Boolean deletedOnly, Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<Item> spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like),
                    cb.like(cb.lower(root.get("pluCode")), like), cb.like(cb.lower(root.get("barcode")), like)));
        }
        return items.findAll(spec, pageable);
    }

    public Page<Item> listItems(Pageable pageable) { return listItems(null, null, null, false, false, pageable); }

    @Transactional(readOnly = true)
    public Item getItem(UUID id) {
        return items.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found"));
    }

    @Transactional(readOnly = true)
    public Item findByBarcodeOrPlu(String code) {
        return items.findByBarcodeAndDeletedAtIsNull(code)
                .or(() -> items.findByPluCodeAndDeletedAtIsNull(code))
                .orElseThrow(() -> new ResourceNotFoundException("Item not found for code " + code));
    }

    @Transactional
    public ItemResponse createItem(ItemRequest req) {
        if (items.existsByPluCode(req.pluCode())) {
            throw new ConflictException("An item with this PLU code already exists");
        }
        Item item = new Item();
        applyItem(item, req);
        item.setTotalQuantity(item.getStockQuantity());
        items.save(item);
        syncHub.broadcast("ITEM_CREATED", "item", Map.of("itemId", item.getId().toString()));
        return toItemResponse(item);
    }

    @Transactional
    public ItemResponse updateItem(UUID id, ItemRequest req) {
        Item item = getItem(id);
        applyItem(item, req);
        items.save(item);
        syncHub.broadcast("ITEM_UPDATED", "item", Map.of("itemId", id.toString()));
        return toItemResponse(item);
    }

    @Transactional
    public void deleteItem(UUID id, boolean hard) {
        if (hard) {
            items.findById(id).ifPresent(item -> {
                items.delete(item);
                syncHub.broadcast("ITEM_HARD_DELETED", "item", Map.of("itemId", id.toString()));
            });
            return;
        }
        Item item = getItem(id);
        item.setDeletedAt(Instant.now());
        items.save(item);
        syncHub.broadcast("ITEM_DELETED", "item", Map.of("itemId", id.toString()));
    }

    @Transactional
    public Item restoreItem(UUID id) {
        Item item = items.findById(id).orElseThrow(() -> new ResourceNotFoundException("Item not found"));
        item.setDeletedAt(null);
        return items.save(item);
    }

    @Transactional
    public int bulkDelete(java.util.List<UUID> ids) {
        int count = 0;
        for (UUID id : ids) {
            items.findByIdAndDeletedAtIsNull(id).ifPresent(item -> {
                item.setDeletedAt(Instant.now());
                items.save(item);
            });
            count++;
        }
        syncHub.broadcast("ITEM_MUTATED", "item", Map.of("deleted", String.valueOf(count)));
        return count;
    }

    private void applyItem(Item item, ItemRequest req) {
        Category category = categories.findByIdAndDeletedAtIsNull(req.categoryId())
                .orElseThrow(() -> new BadRequestException("Category not found"));
        item.setPluCode(req.pluCode());
        item.setName(req.name());
        item.setDescription(req.description());
        item.setCategory(category);
        item.setBarcode(req.barcode());
        item.setQrCode(req.qrCode());
        item.setPrice(req.price());
        item.setCostPrice(req.costPrice() == null ? BigDecimal.ZERO : req.costPrice());
        if (req.unit() != null) item.setUnit(req.unit());
        if (req.stockQuantity() != null) item.setStockQuantity(req.stockQuantity());
        if (req.lowStockThreshold() != null) item.setLowStockThreshold(req.lowStockThreshold());
        if (req.active() != null) item.setActive(req.active());
        item.setHasDiscount(Boolean.TRUE.equals(req.hasDiscount()));
        item.setDiscountPercentage(req.discountPercentage() == null ? BigDecimal.ZERO : req.discountPercentage());
    }

    // ---------- promotions ----------
    @Transactional(readOnly = true)
    public Page<Promotion> listPromotions(String search, Boolean includeDeleted, Boolean deletedOnly, Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<Promotion> spec;
        if (Boolean.TRUE.equals(deletedOnly)) {
            spec = (root, q, cb) -> cb.isNotNull(root.get("deletedAt"));
        } else if (Boolean.TRUE.equals(includeDeleted)) {
            spec = (root, q, cb) -> cb.conjunction();
        } else {
            spec = (root, q, cb) -> cb.isNull(root.get("deletedAt"));
        }
        if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            spec = spec.and((root, q, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("description")), like)));
        }
        return promotions.findAll(spec, pageable);
    }
    public Page<Promotion> listPromotions(Pageable pageable) { return listPromotions(null, false, false, pageable); }

    @Transactional
    public PromotionResponse createPromotion(PromotionRequest req) {
        Promotion p = new Promotion();
        applyPromotion(p, req);
        promotions.save(p);
        syncHub.broadcast("PROMOTION_CREATED", "promotion", Map.of("promotionId", p.getId().toString()));
        return toPromotionResponse(p);
    }

    @Transactional
    public PromotionResponse updatePromotion(UUID id, PromotionRequest req) {
        Promotion p = promotions.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        applyPromotion(p, req);
        promotions.save(p);
        syncHub.broadcast("PROMOTION_UPDATED", "promotion", Map.of("promotionId", id.toString()));
        return toPromotionResponse(p);
    }

    @Transactional
    public void deletePromotion(UUID id, boolean hard) {
        if (hard) {
            promotions.findById(id).ifPresent(p -> {
                promotions.delete(p);
                syncHub.broadcast("PROMOTION_HARD_DELETED", "promotion", Map.of("promotionId", id.toString()));
            });
            return;
        }
        Promotion p = promotions.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        p.setDeletedAt(Instant.now());
        promotions.save(p);
        syncHub.broadcast("PROMOTION_DELETED", "promotion", Map.of("promotionId", id.toString()));
    }

    @Transactional
    public Promotion restorePromotion(UUID id) {
        Promotion p = promotions.findById(id).orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        p.setDeletedAt(null);
        return promotions.save(p);
    }

    private void applyPromotion(Promotion p, PromotionRequest req) {
        if (req.itemId() == null && req.categoryId() == null) {
            throw new BadRequestException("Promotion must target an item or a category");
        }
        if (req.itemId() != null) {
            p.setItem(items.findByIdAndDeletedAtIsNull(req.itemId())
                    .orElseThrow(() -> new BadRequestException("Item not found")));
        }
        if (req.categoryId() != null) {
            p.setCategory(categories.findByIdAndDeletedAtIsNull(req.categoryId())
                    .orElseThrow(() -> new BadRequestException("Category not found")));
        }
        p.setName(req.name());
        p.setDescription(req.description());
        try {
            p.setDiscountType(DiscountType.valueOf(req.discountType()));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("discountType must be 'percentage' or 'fixed'");
        }
        p.setDiscountValue(req.discountValue());
        p.setStartDate(req.startDate());
        if (req.endDate().isBefore(req.startDate())) throw new BadRequestException("Promotion end date must be after start date");
        p.setEndDate(req.endDate());
        p.setMinItemCount(req.minItemCount() == null ? 1 : req.minItemCount());
        p.setActive(req.active() == null || req.active());
    }

    // ---------- mappers ----------
    public static CategoryResponse toCategoryResponse(Category c) {
        return new CategoryResponse(c.getId(), c.getName(), c.getDescription(), c.isActive(),
                c.getCreatedAt(), c.getUpdatedAt());
    }

    public static ItemResponse toItemResponse(Item i) {
        return new ItemResponse(i.getId(), i.getPluCode(), i.getName(), i.getDescription(),
                i.getCategory().getId(), i.getCategory().getName(), i.getBarcode(), i.getQrCode(),
                i.getPrice(), i.getCostPrice(), i.getUnit(), i.getStockQuantity(), i.getLowStockThreshold(),
                i.getVersion(), i.isActive(), i.isHasDiscount(), i.getDiscountPercentage(),
                i.getCreatedAt(), i.getUpdatedAt());
    }

    public static PromotionResponse toPromotionResponse(Promotion p) {
        return new PromotionResponse(p.getId(),
                p.getItem() == null ? null : p.getItem().getId(),
                p.getItem() == null ? null : p.getItem().getName(),
                p.getCategory() == null ? null : p.getCategory().getId(),
                p.getCategory() == null ? null : p.getCategory().getName(),
                p.getName(), p.getDescription(), p.getDiscountType().name(), p.getDiscountValue(),
                p.getStartDate(), p.getEndDate(), p.isActive(), p.getMinItemCount());
    }
}
