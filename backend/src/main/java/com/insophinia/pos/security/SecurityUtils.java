package com.insophinia.pos.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class SecurityUtils {

    private SecurityUtils() {}

    public static Optional<AuthUser> currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser au && au.role() != null) {
            return Optional.of(au);
        }
        return Optional.empty();
    }

    public static AuthUser requireUser() {
        return currentUser().orElseThrow(() -> new com.insophinia.pos.exception.UnauthorizedException("Authentication required"));
    }
}
