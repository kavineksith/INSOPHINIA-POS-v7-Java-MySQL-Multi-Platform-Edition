package com.insophinia.pos.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.insophinia.pos.service.SystemService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Map;

/** Enforces the dead-man lock across API routes; only status, health, login and authorized unlock remain reachable. */
@Component
public class SystemLockFilter extends OncePerRequestFilter {
    private final SystemService system; private final ObjectMapper mapper = new ObjectMapper();
    public SystemLockFilter(SystemService system) { this.system = system; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return !p.startsWith("/api/") || p.equals("/api/system/health") || p.equals("/api/system/status")
                || p.equals("/api/auth/login") || p.equals("/api/auth/logout") || p.equals("/api/auth/2fa/verify");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && system.status().locked()) {
            boolean canUnlock = request.getRequestURI().equals("/api/system/lock")
                    && "POST".equalsIgnoreCase(request.getMethod())
                    && auth.getAuthorities().stream().anyMatch(a -> "manage:system_lock".equals(a.getAuthority()));
            if (!canUnlock) { response.setStatus(423); response.setContentType("application/json");
                mapper.writeValue(response.getWriter(), Map.of("success", false, "message", "System is locked", "code", "SYSTEM_LOCKED")); return; }
        }
        chain.doFilter(request, response);
    }
}
