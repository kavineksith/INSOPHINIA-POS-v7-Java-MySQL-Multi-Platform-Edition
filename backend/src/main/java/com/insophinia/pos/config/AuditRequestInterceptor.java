package com.insophinia.pos.config;

import com.insophinia.pos.entity.AuditLog;
import com.insophinia.pos.repository.AuditLogRepository;
import com.insophinia.pos.security.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Append-only request audit for every mutating API call; never records request bodies or secrets. */
@Component
public class AuditRequestInterceptor implements HandlerInterceptor {
    private final AuditLogRepository repository;
    public AuditRequestInterceptor(AuditLogRepository repository) { this.repository = repository; }

    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!request.getRequestURI().startsWith("/api/")) return;
        String method = request.getMethod();
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method))) return;
        String path = request.getRequestURI();
        if (path.startsWith("/api/sync/")) return;
        try {
            AuditLog entry = new AuditLog();
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof AuthUser user) entry.setUserId(user.userId());
            entry.setAction(method + " " + path);
            entry.setEntity(path.split("/").length > 2 ? path.split("/")[2] : "api");
            entry.setEntityId(request.getPathInfo());
            entry.setDetails("status=" + response.getStatus() + (ex == null ? "" : "; exception=" + ex.getClass().getSimpleName()));
            entry.setIpAddress(request.getRemoteAddr()); // Trust proxy headers only when a trusted-proxy configuration is implemented.
            entry.setUserAgent(request.getHeader("User-Agent"));
            repository.save(entry);
        } catch (RuntimeException ignored) {
            // Audit failure must not replace the original API response; database monitoring should alert on it.
        }
    }
}
