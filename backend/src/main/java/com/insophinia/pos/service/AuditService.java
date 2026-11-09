package com.insophinia.pos.service;

import com.insophinia.pos.entity.AuditLog;
import com.insophinia.pos.repository.AuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditService {

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void log(UUID userId, String action, String entity, String entityId,
                    String details, HttpServletRequest request) {
        AuditLog log = new AuditLog();
        log.setUserId(userId);
        log.setAction(action);
        log.setEntity(entity);
        log.setEntityId(entityId);
        log.setDetails(details);
        if (request != null) {
            log.setIpAddress(clientIp(request));
            log.setUserAgent(request.getHeader("User-Agent"));
        }
        repository.save(log);
    }

    public static String clientIp(HttpServletRequest request) {
        return request.getRemoteAddr(); // Use forwarded headers only behind an explicitly trusted proxy.
    }
}
