package com.insophinia.pos.repository;

import com.insophinia.pos.entity.Category;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    Optional<Category> findByIdAndDeletedAtIsNull(UUID id);
    Optional<Category> findByNameAndDeletedAtIsNull(String name);
    boolean existsByNameIgnoreCaseAndDeletedAtIsNull(String name);
    Page<Category> findByDeletedAtIsNullAndNameContainingIgnoreCase(String name, Pageable pageable);
    Page<Category> findByDeletedAtIsNull(Pageable pageable);
}
