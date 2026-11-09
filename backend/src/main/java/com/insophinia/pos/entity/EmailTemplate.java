package com.insophinia.pos.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * User-defined email template for receipts + marketing.
 *
 * The body is HTML with a strict variable allowlist (e.g. {{billNumber}},
 * {{customerName}}, {{totalAmount}}). Variables are substituted server-side
 * before send; arbitrary template variables are rejected at validation
 * time, and HTML is sanitized through Jsoup before being persisted (so the
 * operator can't persist an XSS payload as a template).
 *
 * {@code kind} discriminates receipt / marketing / test / welcome / custom
 * templates so the UI can group them.
 *
 * {@code is_default} marks the default template of a given kind — used when
 * a bill is sent without an explicit template selection.
 */
@Getter
@Setter
@Entity
@Table(name = "email_templates")
public class EmailTemplate extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private EmailTemplateKind kind = EmailTemplateKind.custom;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "MEDIUMTEXT")
    private String bodyHtml;

    @Column(name = "body_text", columnDefinition = "MEDIUMTEXT")
    private String bodyText;

    /**
     * CSV allowlist of variable names this template may reference. Validated
     * against the canonical variable registry in {@link
     * com.insophinia.pos.service.EmailTemplateService#VARIABLE_REGISTRY}.
     */
    @Column(name = "allowed_variables", length = 500)
    private String allowedVariables;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;
}
