package com.insophinia.pos.controller;

import com.insophinia.pos.dto.ApiResponse;
import com.insophinia.pos.dto.AuthDtos.ChangePasswordRequest;
import com.insophinia.pos.dto.AuthDtos.LoginRequest;
import com.insophinia.pos.dto.AuthDtos.LoginResponse;
import com.insophinia.pos.dto.AuthDtos.SessionResponse;
import com.insophinia.pos.dto.AuthDtos.TwoFactorDisableRequest;
import com.insophinia.pos.dto.AuthDtos.TwoFactorEnableRequest;
import com.insophinia.pos.dto.AuthDtos.TwoFactorSetupRequest;
import com.insophinia.pos.dto.AuthDtos.TwoFactorSetupResponse;
import com.insophinia.pos.dto.AuthDtos.TwoFactorVerifyRequest;
import com.insophinia.pos.dto.MiscDtos.SecurityEventResponse;
import com.insophinia.pos.entity.LoginSession;
import com.insophinia.pos.exception.RateLimitExceededException;
import com.insophinia.pos.exception.UnauthorizedException;
import com.insophinia.pos.repository.SecurityEventRepository;
import com.insophinia.pos.security.AuthUser;
import com.insophinia.pos.security.RateLimitService;
import com.insophinia.pos.security.SecurityUtils;
import com.insophinia.pos.security.SessionService;
import com.insophinia.pos.service.AuditService;
import com.insophinia.pos.service.AuthService;
import com.insophinia.pos.service.TwoFactorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final TwoFactorService twoFactorService;
    private final SessionService sessionService;
    private final RateLimitService rateLimitService;
    private final SecurityEventRepository securityEvents;

    public AuthController(AuthService authService, TwoFactorService twoFactorService,
                          SessionService sessionService, RateLimitService rateLimitService,
                          SecurityEventRepository securityEvents) {
        this.authService = authService;
        this.twoFactorService = twoFactorService;
        this.sessionService = sessionService;
        this.rateLimitService = rateLimitService;
        this.securityEvents = securityEvents;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        String ip = AuditService.clientIp(request);
        if (!rateLimitService.allow(ip, "login")) {
            throw new RateLimitExceededException("Too many login attempts. Please try again later.");
        }
        return ApiResponse.ok(authService.login(req.username(), req.password(), request), "Login successful");
    }

    @PostMapping("/2fa/verify")
    public ApiResponse<LoginResponse> verify2fa(@Valid @RequestBody TwoFactorVerifyRequest req,
                                                HttpServletRequest request) {
        return ApiResponse.ok(authService.verifyTwoFactor(req.pendingToken(), req.code(), req.backupCode(), request));
    }

    @PostMapping("/2fa/setup")
    public ApiResponse<TwoFactorSetupResponse> setup2fa(@Valid @RequestBody TwoFactorSetupRequest req) {
        AuthUser user = SecurityUtils.requireUser();
        return ApiResponse.ok(twoFactorService.setup(user.userId(), req.password()));
    }

    @PostMapping("/2fa/enable")
    public ApiResponse<Map<String, Boolean>> enable2fa(@Valid @RequestBody TwoFactorEnableRequest req) {
        AuthUser user = SecurityUtils.requireUser();
        twoFactorService.enable(user.userId(), req.code());
        return ApiResponse.ok(Map.of("twoFactorEnabled", true), "Two-factor authentication enabled");
    }

    @PostMapping("/2fa/disable")
    public ApiResponse<Map<String, Boolean>> disable2fa(@Valid @RequestBody TwoFactorDisableRequest req) {
        AuthUser user = SecurityUtils.requireUser();
        twoFactorService.disable(user.userId(), req.password());
        return ApiResponse.ok(Map.of("twoFactorEnabled", false), "Two-factor authentication disabled");
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(HttpServletRequest request) {
        AuthUser user = SecurityUtils.requireUser();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (token != null) {
            authService.logout(token, new AuthService.AuthUserContext(user.userId(), user.sessionToken()), request);
        }
        return ApiResponse.ok(Map.of("loggedOut", true), "Logged out");
    }

    @PostMapping("/change-password")
    public ApiResponse<Map<String, Boolean>> changePassword(@Valid @RequestBody ChangePasswordRequest req,
                                                            HttpServletRequest request) {
        AuthUser user = SecurityUtils.requireUser();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        authService.changePassword(user.userId(), req, token);
        return ApiResponse.ok(Map.of("changed", true), "Password changed. Please log in again.");
    }

    @GetMapping("/sessions")
    public ApiResponse<List<SessionResponse>> sessions() {
        AuthUser user = SecurityUtils.requireUser();
        List<SessionResponse> sessions = sessionService.activeSessions(user.userId()).stream()
                .map(s -> toSessionResponse(s, user.sessionToken()))
                .toList();
        return ApiResponse.ok(sessions);
    }

    @PostMapping("/sessions/{id}/revoke")
    public ApiResponse<Map<String, Boolean>> revokeSession(@PathVariable UUID id) {
        AuthUser user = SecurityUtils.requireUser();
        if (!sessionService.revoke(id, user.userId(), "user_revoked")) {
            throw new com.insophinia.pos.exception.ResourceNotFoundException("Session not found");
        }
        return ApiResponse.ok(Map.of("revoked", true));
    }

    @PostMapping("/sessions/revoke-all")
    public ApiResponse<Map<String, Integer>> revokeAllSessions() {
        AuthUser user = SecurityUtils.requireUser();
        int count = sessionService.revokeAll(user.userId(), "revoke_all");
        return ApiResponse.ok(Map.of("revoked", count));
    }

    @GetMapping("/security-events")
    public ApiResponse<List<SecurityEventResponse>> securityEvents(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        AuthUser user = SecurityUtils.requireUser();
        var events = securityEvents.findByUserIdOrderByCreatedAtDesc(user.userId(), PageRequest.of(page, size));
        return ApiResponse.ok(events.map(e -> new SecurityEventResponse(e.getId(), e.getUserId(), e.getEventType(),
                e.getIpAddress(), e.getDetails(), e.getSeverity(), e.getCreatedAt())).getContent());
    }

    private static SessionResponse toSessionResponse(LoginSession s, String currentToken) {
        return new SessionResponse(s.getId(), s.getIpAddress(), s.getBrowser(), s.getOs(), s.getDevice(),
                s.getCreatedAt().toString(), s.getLastActivityAt().toString(), s.getExpiresAt().toString(),
                s.getSessionToken().equals(currentToken));
    }
}
