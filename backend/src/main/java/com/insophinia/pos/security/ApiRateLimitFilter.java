package com.insophinia.pos.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

/** Basic per-IP API abuse guard; production multi-instance deployments should use a shared limiter. */
@Component
public class ApiRateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    public ApiRateLimitFilter(RateLimitService limits) { this.limits = limits; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/") || request.getRequestURI().equals("/api/system/health");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        // Do not trust X-Forwarded-For unless a trusted-proxy allow-list is configured.
        String ip = request.getRemoteAddr();
        if (!limits.allow(ip, "api")) {
            response.setStatus(429); response.setHeader("Retry-After", "60"); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"success\":false,\"message\":\"Too many requests; retry shortly\",\"code\":\"RATE_LIMITED\"}"); return;
        }
        chain.doFilter(request, response);
    }
}
