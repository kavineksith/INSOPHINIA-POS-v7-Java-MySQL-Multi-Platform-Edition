package com.insophinia.pos.repository;

import com.insophinia.pos.entity.EmailTemplate;
import com.insophinia.pos.entity.EmailTemplateKind;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, UUID> {
    Optional<EmailTemplate> findByIdAndDeletedAtIsNull(UUID id);
    List<EmailTemplate> findByDeletedAtIsNullOrderByNameAsc();
    List<EmailTemplate> findByKindAndDeletedAtIsNullOrderByNameAsc(EmailTemplateKind kind);
    Optional<EmailTemplate> findByKindAndIsDefaultTrueAndDeletedAtIsNull(EmailTemplateKind kind);

    /**
     * Demote any existing default of the same kind so a newly-set default is
     * the unique default. Used by {@code EmailTemplateService.setDefault}.
     */
    @Modifying
    @Query("update EmailTemplate t set t.isDefault = false " +
            "where t.kind = :kind and t.deletedAt is null and t.isDefault = true")
    int clearDefaultForKind(@Param("kind") EmailTemplateKind kind);
}
