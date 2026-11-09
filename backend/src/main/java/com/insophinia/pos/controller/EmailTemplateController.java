package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.PagedResponse;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateRenderRequest;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateRenderResponse;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateRequest;
import com.insophinia.pos.dto.V7Dtos.EmailTemplateResponse;
import com.insophinia.pos.entity.EmailTemplate;
import com.insophinia.pos.entity.EmailTemplateKind;
import com.insophinia.pos.security.Permissions;
import com.insophinia.pos.service.EmailTemplateService;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Email template CRUD + render-preview endpoint.
 *
 * RBAC:
 *   - view:email_templates (supervisor+): GET endpoints
 *   - manage:email_templates (admin+): POST/PUT/DELETE
 *
 * The render-preview endpoint is admin+ only — even though it doesn't persist,
 * the rendered body could leak variable values to a less-privileged role.
 */
@RestController
@RequestMapping("/api/email-templates")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) { this.service = service; }

    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_EMAIL_TEMPLATES + "')")
    public ApiResponse<List<EmailTemplateResponse>> list(@RequestParam(required = false) String kind) {
        List<EmailTemplate> templates = (kind == null || kind.isBlank())
                ? service.list()
                : service.listByKind(EmailTemplateKind.valueOf(kind));
        return ApiResponse.ok(templates.stream().map(EmailTemplateService::toResponse).toList());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.VIEW_EMAIL_TEMPLATES + "')")
    public ApiResponse<EmailTemplateResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(EmailTemplateService.toResponse(service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_EMAIL_TEMPLATES + "')")
    public ApiResponse<EmailTemplateResponse> create(@Valid @RequestBody EmailTemplateRequest req) {
        return ApiResponse.ok(service.create(req), "Email template created");
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_EMAIL_TEMPLATES + "')")
    public ApiResponse<EmailTemplateResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody EmailTemplateRequest req) {
        return ApiResponse.ok(service.update(id, req), "Email template updated");
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_EMAIL_TEMPLATES + "')")
    public ApiResponse<Map<String, Boolean>> delete(@PathVariable UUID id) {
        service.delete(id);
        return ApiResponse.ok(Map.of("deleted", true), "Email template deleted");
    }

    @PostMapping("/{id}/set-default")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_EMAIL_TEMPLATES + "')")
    public ApiResponse<EmailTemplateResponse> setDefault(@PathVariable UUID id) {
        return ApiResponse.ok(service.setDefault(id), "Default template updated");
    }

    /**
     * Render-preview endpoint — substitutes the supplied variables and returns
     * the rendered HTML. Useful for the UI's template editor preview pane.
     */
    @PostMapping("/{id}/render")
    @PreAuthorize("hasAuthority('" + Permissions.MANAGE_EMAIL_TEMPLATES + "')")
    public ApiResponse<EmailTemplateRenderResponse> render(
            @PathVariable UUID id,
            @Valid @RequestBody EmailTemplateRenderRequest req) {
        return ApiResponse.ok(service.render(service.get(id), req.variables()));
    }
}
