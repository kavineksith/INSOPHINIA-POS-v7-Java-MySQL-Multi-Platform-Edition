package com.insophinia.pos.service;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link AuditService#clientIp(HttpServletRequest)}.
 *
 * The security-static-review (2026-10-15) explicitly removed default trust in
 * the caller-supplied {@code X-Forwarded-For} header. IP-based audit fields
 * now always reflect the socket-level peer address ({@code getRemoteAddr()}).
 * If a deployment runs behind a trusted L7 proxy that populates
 * {@code X-Forwarded-For}, IP pinning should be configured at the proxy layer,
 * not in application code.
 */
class AuditServiceTest {

    @Test
    void clientIpUsesRemoteAddressWhenForwardedHeaderIsAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        assertEquals("192.0.2.10", AuditService.clientIp(request));
    }

    @Test
    void clientIpIgnoresForwardedHeaderByDefaultForSecurity() {
        // An attacker may forge X-Forwarded-For. The current implementation
        // intentionally ignores it; the audit row records the actual TCP peer.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        request.addHeader("X-Forwarded-For", "198.51.100.7, 192.0.2.10");
        assertEquals("192.0.2.10", AuditService.clientIp(request));
    }
}
