package com.insophinia.pos.security;

import com.insophinia.pos.entity.UserRole;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Extracts and validates the Bearer JWT; rejects blacklisted tokens and inactive sessions. */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final SessionService sessionService;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   TokenBlacklistService blacklistService,
                                   SessionService sessionService) {
        this.jwtService = jwtService;
        this.blacklistService = blacklistService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = jwtService.parse(token);
                boolean access = "access".equals(claims.get("type", String.class));
                boolean pending2fa = "pending_2fa".equals(claims.get("type", String.class));

                if (!blacklistService.isBlacklisted(token)) {
                    UUID userId = jwtService.userId(claims);
                    String username = claims.get("username", String.class);
                    String sessionToken = claims.get("session", String.class);
                    UserRole role = access ? UserRole.valueOf(claims.get("role", String.class)) : null;

                    // Access tokens additionally require a live session.
                    if (access && (sessionToken == null || !sessionService.isSessionActive(sessionToken))) {
                        chain.doFilter(request, response);
                        return;
                    }

                    AuthUser principal = new AuthUser(userId, username, role, sessionToken);
                    var auth = pending2fa
                            ? new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of())
                            : new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // invalid token -> request stays unauthenticated; entry point returns 401
            }
        }
        chain.doFilter(request, response);
    }
}
