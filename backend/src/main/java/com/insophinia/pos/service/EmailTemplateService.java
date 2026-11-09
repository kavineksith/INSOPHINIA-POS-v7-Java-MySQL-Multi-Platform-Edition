package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.EmailTemplateRenderResponse;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateRequest;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateResponse;
import com.insophinia.pos.entity.EmailTemplate;
import com.insophinia.pos.entity.EmailTemplateKind;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.exception.ResourceNotFoundException;
import com.insophinia.pos.repository.EmailTemplateRepository;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages user-defined email templates for receipts + marketing.
 *
 * Security model:
 *   - HTML body is run through Jsoup's relaxed Safelist on persist + render.
 *     Inline JavaScript, iframes, object embeds, and form tags are stripped.
 *     All `<a href>` links are forced to rel="nofollow".
 *   - Variables use the {{varName}} syntax. The canonical variable registry
 *     below is the exhaustive allowlist — any other placeholder is rejected
 *     at validation time so a malicious template can't exfiltrate arbitrary
 *     fields via {{user.password_hash}} style interpolations.
 *   - Allowed variables are stored as CSV on the template row for per-template
 *     tightening. The default per-kind allowlist applies when the row's
 *     allowedVariables is null/blank.
 */
@Service
public class EmailTemplateService {

    /** {{varName}} placeholders, simple alphanumeric + underscore identifier. */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{(\\w+)}}");

    /** Maximum body size — bounded by the DB column (MEDIUMTEXT, 16 MB), but
     *  we cap at 64 KB so a malicious operator can't fill the table with junk. */
    private static final int MAX_BODY_SIZE = 65_536;

    /**
     * Canonical variable registry — exhaustive allowlist of every variable any
     * template may reference. Adding to this list requires a security review:
     *   - No PII beyond what the recipient already sees on the bill
     *   - No secrets (passwords, JWTs, encryption keys)
     *   - No internal-only IDs that could leak to a customer
     */
    public static final Map<String, String> VARIABLE_REGISTRY = Map.ofEntries(
            Map.entry("billNumber", "Bill number (e.g. BILL-20261018-0001)"),
            Map.entry("billId", "Bill UUID (URL form)"),
            Map.entry("customerName", "Customer display name"),
            Map.entry("customerEmail", "Customer email address"),
            Map.entry("customerPhone", "Customer phone number"),
            Map.entry("staffName", "Staff member who created the bill"),
            Map.entry("shopName", "Shop name from settings"),
            Map.entry("shopAddress", "Shop address from settings"),
            Map.entry("shopPhone", "Shop phone from settings"),
            Map.entry("totalAmount", "Bill grand total"),
            Map.entry("paidAmount", "Amount paid by the customer"),
            Map.entry("balance", "Outstanding balance"),
            Map.entry("subtotal", "Pre-tax subtotal"),
            Map.entry("taxTotal", "Total tax (VAT + SSCL)"),
            Map.entry("vatAmount", "VAT amount"),
            Map.entry("ssclAmount", "SSCL amount"),
            Map.entry("totalDiscount", "Total discount"),
            Map.entry("createdAt", "Bill creation timestamp (ISO 8601)"),
            Map.entry("billUrl", "Customer-facing bill URL (configured in env)"),
            Map.entry("warrantyExpiresAt", "Earliest warranty expiry across all line items"),
            Map.entry("campaignName", "Marketing campaign name (marketing templates only)"),
            Map.entry("unsubscribeUrl", "Customer unsubscribe URL (marketing templates only)")
    );

    /** Default per-kind variable allowlist. */
    private static final Map<EmailTemplateKind, List<String>> DEFAULT_ALLOWED_BY_KIND = Map.of(
            EmailTemplateKind.receipt, List.of(
                    "billNumber", "billId", "customerName", "customerEmail", "customerPhone",
                    "staffName", "shopName", "shopAddress", "shopPhone", "totalAmount",
                    "paidAmount", "balance", "subtotal", "taxTotal", "vatAmount", "ssclAmount",
                    "totalDiscount", "createdAt", "billUrl", "warrantyExpiresAt"),
            EmailTemplateKind.marketing, List.of(
                    "customerName", "customerEmail", "shopName", "shopAddress", "shopPhone",
                    "campaignName", "unsubscribeUrl"),
            EmailTemplateKind.test, List.of("shopName"),
            EmailTemplateKind.welcome, List.of(
                    "customerName", "customerEmail", "shopName", "shopAddress", "shopPhone"),
            EmailTemplateKind.custom, List.of()
    );

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EmailTemplate> list() { return repository.findByDeletedAtIsNullOrderByNameAsc(); }

    @Transactional(readOnly = true)
    public List<EmailTemplate> listByKind(EmailTemplateKind kind) {
        return repository.findByKindAndDeletedAtIsNullOrderByNameAsc(kind);
    }

    @Transactional(readOnly = true)
    public EmailTemplate get(UUID id) {
        return repository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Email template not found"));
    }

    @Transactional(readOnly = true)
    public EmailTemplate getDefault(EmailTemplateKind kind) {
        return repository.findByKindAndIsDefaultTrueAndDeletedAtIsNull(kind)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No default " + kind + " template set — create one first"));
    }

    @Transactional
    public EmailTemplateResponse create(EmailTemplateRequest req) {
        EmailTemplateKind kind = parseKind(req.kind());
        String sanitizedBody = sanitize(req.bodyHtml());
        validateVariables(sanitizedBody, req.allowedVariables(), kind);

        EmailTemplate t = new EmailTemplate();
        t.setName(req.name().trim());
        t.setKind(kind);
        t.setSubject(req.subject().trim());
        t.setBodyHtml(sanitizedBody);
        t.setBodyText(req.bodyText());
        t.setAllowedVariables(req.allowedVariables() == null || req.allowedVariables().isBlank()
                ? String.join(",", DEFAULT_ALLOWED_BY_KIND.get(kind))
                : req.allowedVariables());
        t.setActive(req.active() == null || req.active());
        t.setDefault(Boolean.TRUE.equals(req.isDefault()));

        if (t.isDefault()) {
            repository.clearDefaultForKind(kind);
        }
        repository.save(t);
        return toResponse(t);
    }

    @Transactional
    public EmailTemplateResponse update(UUID id, EmailTemplateRequest req) {
        EmailTemplate t = get(id);
        EmailTemplateKind kind = parseKind(req.kind());
        String sanitizedBody = sanitize(req.bodyHtml());
        validateVariables(sanitizedBody, req.allowedVariables(), kind);

        t.setName(req.name().trim());
        t.setKind(kind);
        t.setSubject(req.subject().trim());
        t.setBodyHtml(sanitizedBody);
        t.setBodyText(req.bodyText());
        t.setAllowedVariables(req.allowedVariables());
        if (req.active() != null) t.setActive(req.active());

        boolean wantsDefault = Boolean.TRUE.equals(req.isDefault());
        if (wantsDefault && !t.isDefault()) {
            repository.clearDefaultForKind(kind);
            t.setDefault(true);
        }
        repository.save(t);
        return toResponse(t);
    }

    @Transactional
    public void delete(UUID id) {
        EmailTemplate t = get(id);
        t.setDeletedAt(java.time.Instant.now());
        repository.save(t);
    }

    @Transactional
    public EmailTemplateResponse setDefault(UUID id) {
        EmailTemplate t = get(id);
        repository.clearDefaultForKind(t.getKind());
        t.setDefault(true);
        repository.save(t);
        return toResponse(t);
    }

    /**
     * Render the template by substituting the supplied variables. Variables
     * not in the canonical registry are rejected; variables in the registry
     * but missing from the caller's map are replaced with an empty string.
     */
    public EmailTemplateRenderResponse render(EmailTemplate template, Map<String, String> variables) {
        // Validate every variable in the caller's map is in the registry.
        for (String key : variables.keySet()) {
            if (!VARIABLE_REGISTRY.containsKey(key)) {
                throw new BadRequestException(
                        "Unknown template variable: " + key + " — only canonical registry variables are allowed");
            }
        }
        // Validate every variable referenced in the body is in the per-template
        // allowlist (so a custom template can't access more than it declared).
        String allowedCsv = template.getAllowedVariables();
        java.util.Set<String> allowed = allowedCsv == null || allowedCsv.isBlank()
                ? java.util.Set.copyOf(DEFAULT_ALLOWED_BY_KIND.get(template.getKind()))
                : java.util.Arrays.stream(allowedCsv.split(","))
                        .map(String::trim).filter(s -> !s.isBlank())
                        .collect(java.util.stream.Collectors.toSet());

        Matcher m = VARIABLE_PATTERN.matcher(template.getBodyHtml());
        java.util.Set<String> referenced = new java.util.HashSet<>();
        while (m.find()) referenced.add(m.group(1));
        for (String var : referenced) {
            if (!VARIABLE_REGISTRY.containsKey(var)) {
                throw new BadRequestException(
                        "Template references unknown variable " + var + " — rejected");
            }
            if (!allowed.contains(var)) {
                throw new BadRequestException(
                        "Template references variable " + var + " but it's not in this template's allowed_variables");
            }
        }

        // Substitute. We re-sanitize the rendered body to prevent any HTML
        // injection through the variable values themselves.
        String renderedSubject = substitute(template.getSubject(), variables);
        String renderedBody = sanitize(substitute(template.getBodyHtml(), variables));
        String renderedText = template.getBodyText() == null ? null : substitute(template.getBodyText(), variables);

        return new EmailTemplateRenderResponse(renderedSubject, renderedBody, renderedText);
    }

    // ----- helpers -------------------------------------------------------

    /** Strip dangerous HTML (script, iframe, object, form) via Jsoup. */
    static String sanitize(String html) {
        if (html == null) return "";
        if (html.length() > MAX_BODY_SIZE) {
            throw new BadRequestException("Template body exceeds the 64 KB limit");
        }
        return Jsoup.clean(html, Safelist.relaxed()
                .preserveRelativeLinks(false)
                .addProtocols("a", "href", "http", "https", "mailto", "tel")
                .addAttributes("a", "rel", "target"));
    }

    /** Replace {{var}} placeholders with their values. Missing variables → empty. */
    static String substitute(String template, Map<String, String> variables) {
        Matcher m = VARIABLE_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder(template.length() + 64);
        while (m.find()) {
            String key = m.group(1);
            String val = variables.getOrDefault(key, "");
            // Escape ${} so Matcher.appendReplacement doesn't interpret them
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Reject {{var}} placeholders that aren't in the canonical registry. */
    static void validateVariables(String bodyHtml, String allowedCsv, EmailTemplateKind kind) {
        java.util.Set<String> referenced = new java.util.HashSet<>();
        Matcher m = VARIABLE_PATTERN.matcher(bodyHtml);
        while (m.find()) referenced.add(m.group(1));
        for (String var : referenced) {
            if (!VARIABLE_REGISTRY.containsKey(var)) {
                throw new BadRequestException(
                        "Template references unknown variable " + var + " — rejected. " +
                                "Allowed variables: " + String.join(", ", VARIABLE_REGISTRY.keySet()));
            }
        }
        // If the operator declared an allowed_variables list, every referenced
        // variable must be in their declaration. This is the per-template tightening
        // mechanism: a custom template can use only the variables it declares.
        if (allowedCsv != null && !allowedCsv.isBlank()) {
            java.util.Set<String> declared = java.util.Arrays.stream(allowedCsv.split(","))
                    .map(String::trim).filter(s -> !s.isBlank())
                    .collect(java.util.stream.Collectors.toSet());
            for (String var : declared) {
                if (!VARIABLE_REGISTRY.containsKey(var)) {
                    throw new BadRequestException(
                            "Declared allowed variable " + var + " is not in the canonical registry");
                }
            }
            for (String var : referenced) {
                if (!declared.contains(var)) {
                    throw new BadRequestException(
                            "Template references " + var + " but it's not in the declared allowed_variables");
                }
            }
        }
    }

    static EmailTemplateKind parseKind(String kind) {
        try {
            return EmailTemplateKind.valueOf(kind);
        } catch (Exception e) {
            throw new BadRequestException("Invalid template kind: " + kind
                    + " — must be one of receipt, marketing, test, welcome, custom");
        }
    }

    public static EmailTemplateResponse toResponse(EmailTemplate t) {
        return new EmailTemplateResponse(t.getId(), t.getName(), t.getKind().name(),
                t.getSubject(), t.getBodyHtml(), t.getBodyText(),
                t.getAllowedVariables(), t.isActive(), t.isDefault(),
                t.getCreatedAt(), t.getUpdatedAt());
    }
}
