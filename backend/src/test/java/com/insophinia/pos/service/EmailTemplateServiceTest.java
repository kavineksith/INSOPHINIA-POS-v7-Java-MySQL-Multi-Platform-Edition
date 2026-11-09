package com.insophinia.pos.service;

import com.insophinia.pos.dto.V7Dtos.EmailTemplateRequest;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateRenderResponse;
import com.insophinia.pos.entity.EmailTemplate;
import com.insophinia.pos.entity.EmailTemplateKind;
import com.insophinia.pos.exception.BadRequestException;
import com.insophinia.pos.repository.EmailTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EmailTemplateService} — the security-critical
 * service that validates + sanitizes user-authored email templates.
 *
 * Coverage:
 *   - Jsoup HTML sanitization strips <script>, <iframe>, <object>, <form>
 *   - Unknown template variables rejected at create + render time
 *   - Per-template allowed_variables tightening enforced
 *   - Variable substitution produces correct rendered output
 *   - Default per-kind allowlists applied when allowedVariables is blank
 *   - Template body size limit enforced (64 KB cap)
 */
@ExtendWith(MockitoExtension.class)
class EmailTemplateServiceTest {

    @Mock private EmailTemplateRepository repository;

    private EmailTemplateService service;

    @BeforeEach
    void setUp() {
        service = new EmailTemplateService(repository);
    }

    // ===== Sanitization =====

    @Test
    void sanitizeStripsScriptTags() {
        String dirty = "<p>Hello</p><script>alert('xss')</script>";
        String clean = EmailTemplateService.sanitize(dirty);
        assertFalse(clean.contains("<script>"), "Script tag must be stripped");
        assertFalse(clean.contains("alert"), "Script content must be removed");
        assertTrue(clean.contains("Hello"), "Safe content must survive");
    }

    @Test
    void sanitizeStripsIframeAndObjectEmbeds() {
        String dirty = "<p>Receipt</p><iframe src=\"evil.com\"></iframe><object data=\"evil.swf\"></object>";
        String clean = EmailTemplateService.sanitize(dirty);
        assertFalse(clean.contains("<iframe>"), "Iframe must be stripped");
        assertFalse(clean.contains("<object"), "Object embed must be stripped");
        assertTrue(clean.contains("Receipt"));
    }

    @Test
    void sanitizeStripsFormAndInputTags() {
        String dirty = "<p>Receipt</p><form action=\"evil.com\"><input name=\"password\"></form>";
        String clean = EmailTemplateService.sanitize(dirty);
        assertFalse(clean.contains("<form"), "Form must be stripped (prevents form hijacking)");
        assertFalse(clean.contains("<input"), "Input must be stripped");
        assertTrue(clean.contains("Receipt"));
    }

    @Test
    void sanitizeRejectsBodyExceeding64KB() {
        StringBuilder huge = new StringBuilder(70_000);
        for (int i = 0; i < 70_000; i++) huge.append('x');
        assertThrows(BadRequestException.class, () -> EmailTemplateService.sanitize(huge.toString()),
                "Template body > 64 KB must be rejected to prevent DB junk flooding");
    }

    @Test
    void sanitizePreservesSafeHtmlStructure() {
        String safe = "<h2>Receipt</h2><p>Bill: <strong>{{billNumber}}</strong></p><ul><li>Item 1</li></ul>";
        String clean = EmailTemplateService.sanitize(safe);
        assertTrue(clean.contains("<h2>"), "Headings preserved");
        assertTrue(clean.contains("<strong>"), "Strong preserved");
        assertTrue(clean.contains("<ul>"), "Lists preserved");
        assertTrue(clean.contains("{{billNumber}}"), "Variable placeholders preserved");
    }

    // ===== Variable validation =====

    @Test
    void validateVariablesRejectsUnknownVariable() {
        String body = "<p>{{password_hash}}</p>"; // not in the canonical registry
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> EmailTemplateService.validateVariables(body, null, EmailTemplateKind.custom));
        assertTrue(ex.getMessage().contains("password_hash"),
                "Error must name the offending variable");
        assertTrue(ex.getMessage().contains("unknown variable"),
                "Error must explain the rejection");
    }

    @Test
    void validateVariablesAcceptsCanonicalVariables() {
        String body = "<p>{{customerName}}, your bill {{billNumber}} total is {{totalAmount}}.</p>";
        assertDoesNotThrow(() ->
                EmailTemplateService.validateVariables(body, null, EmailTemplateKind.receipt),
                "All three variables are in the canonical registry and in the receipt-kind default allowlist");
    }

    @Test
    void validateVariablesRejectsVariableNotInPerTemplateAllowlist() {
        // Template declares only customerName + billNumber in allowedVariables,
        // but the body references totalAmount (which IS canonical but NOT declared).
        String body = "<p>{{customerName}} bill {{billNumber}} total {{totalAmount}}</p>";
        String allowed = "customerName,billNumber";
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> EmailTemplateService.validateVariables(body, allowed, EmailTemplateKind.custom));
        assertTrue(ex.getMessage().contains("totalAmount"),
                "Error must name the undeclared variable");
    }

    @Test
    void validateVariablesRejectsDeclaredButNonCanonicalVariable() {
        String body = "<p>Hello</p>"; // body is safe
        String allowed = "customerName,evil_field"; // evil_field is not canonical
        BadRequestException ex = assertThrows(BadRequestException.class,
                () -> EmailTemplateService.validateVariables(body, allowed, EmailTemplateKind.custom));
        assertTrue(ex.getMessage().contains("evil_field"),
                "Error must name the non-canonical declared variable");
    }

    // ===== Substitution =====

    @Test
    void substituteReplacesVariablesWithValues() {
        String template = "Hello {{customerName}}, your bill {{billNumber}} is {{totalAmount}}.";
        String result = EmailTemplateService.substitute(template,
                Map.of("customerName", "Alice", "billNumber", "BILL-001", "totalAmount", "Rs. 950.00"));
        assertEquals("Hello Alice, your bill BILL-001 is Rs. 950.00.", result);
    }

    @Test
    void substituteReplacesMissingVariableWithEmptyString() {
        String template = "Hello {{customerName}}, missing: {{unknownVar}}.";
        String result = EmailTemplateService.substitute(template, Map.of("customerName", "Bob"));
        assertEquals("Hello Bob, missing: .", result,
                "Missing variables must be substituted with empty string, not the placeholder");
    }

    @Test
    void substituteHandlesDollarSignsInValues() {
        // Dollar signs in values would break Matcher.appendReplacement if not quoted
        String template = "Price: {{totalAmount}}";
        String result = EmailTemplateService.substitute(template, Map.of("totalAmount", "$100.50"));
        assertEquals("Price: $100.50", result,
                "Dollar signs in values must survive substitution (Matcher.quoteReplacement)");
    }

    // ===== Render =====

    @Test
    void renderProducesSanitizedOutput() {
        EmailTemplate t = new EmailTemplate();
        t.setId(java.util.UUID.randomUUID());
        t.setKind(EmailTemplateKind.receipt);
        t.setSubject("Receipt {{billNumber}}");
        t.setBodyHtml("<p>{{customerName}}</p>");
        t.setAllowedVariables("billNumber,customerName");

        EmailTemplateRenderResponse result = service.render(t,
                Map.of("billNumber", "BILL-001", "customerName", "Alice"));

        assertEquals("Receipt BILL-001", result.subject());
        assertEquals("<p>Alice</p>", result.bodyHtml());
    }

    @Test
    void renderRejectsUnknownVariableInCallerMap() {
        EmailTemplate t = new EmailTemplate();
        t.setKind(EmailTemplateKind.custom);
        t.setSubject("Test");
        t.setBodyHtml("<p>Hi</p>");
        t.setAllowedVariables("customerName");

        assertThrows(BadRequestException.class, () ->
                service.render(t, Map.of("evil_field", "hacker")),
                "Caller passing a variable not in the canonical registry must be rejected");
    }

    @Test
    void renderRejectsVariableNotInTemplateAllowlist() {
        EmailTemplate t = new EmailTemplate();
        t.setKind(EmailTemplateKind.custom);
        t.setSubject("{{billNumber}}");
        t.setBodyHtml("<p>{{billNumber}}</p>");
        t.setAllowedVariables("customerName"); // billNumber NOT declared

        // billNumber IS canonical, but the template didn't declare it
        assertThrows(BadRequestException.class, () ->
                service.render(t, Map.of("billNumber", "BILL-001")),
                "Template referencing an undeclared variable must be rejected at render time");
    }

    // ===== Create + default allowlist =====

    @Test
    void createAppliesDefaultAllowlistWhenAllowedVariablesBlank() {
        when(repository.save(any(EmailTemplate.class))).thenAnswer(inv -> {
            EmailTemplate t = inv.getArgument(0);
            t.setId(java.util.UUID.randomUUID());
            return t;
        });

        service.create(new EmailTemplateRequest(
                "Default receipt", "receipt", "Receipt {{billNumber}}",
                "<p>{{customerName}} — {{billNumber}}</p>",
                null, null, true, false));

        ArgumentCaptor<EmailTemplate> captor = ArgumentCaptor.forClass(EmailTemplate.class);
        verify(repository).save(captor.capture());
        EmailTemplate saved = captor.getValue();

        assertNotNull(saved.getAllowedVariables(),
                "Default allowlist must be applied when allowedVariables is null");
        assertTrue(saved.getAllowedVariables().contains("billNumber"),
                "Receipt-kind default allowlist must include billNumber");
        assertTrue(saved.getAllowedVariables().contains("customerName"),
                "Receipt-kind default allowlist must include customerName");
        assertFalse(saved.getAllowedVariables().contains("campaignName"),
                "Receipt-kind default allowlist must NOT include marketing-only variables");
    }

    @Test
    void createClearsExistingDefaultWhenNewTemplateIsDefault() {
        when(repository.save(any(EmailTemplate.class))).thenAnswer(inv -> {
            EmailTemplate t = inv.getArgument(0);
            t.setId(java.util.UUID.randomUUID());
            return t;
        });

        service.create(new EmailTemplateRequest(
                "New default receipt", "receipt", "Subject",
                "<p>Body {{billNumber}}</p>",
                null, null, true, true)); // isDefault = true

        verify(repository).clearDefaultForKind(EmailTemplateKind.receipt);
    }

    @Test
    void createRejectsInvalidKind() {
        assertThrows(BadRequestException.class, () ->
                service.create(new EmailTemplateRequest(
                        "Bad kind", "invalid_kind", "Subject",
                        "<p>Body</p>", null, null, true, false)),
                "Invalid kind must be rejected with a clear message");
    }
}
