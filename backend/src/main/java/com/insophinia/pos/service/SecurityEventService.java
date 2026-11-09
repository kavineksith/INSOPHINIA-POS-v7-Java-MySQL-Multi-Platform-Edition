package com.insophinia.pos.service;

import com.insophinia.pos.entity.SecurityEvent;
import com.insophinia.pos.repository.SecurityEventRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SecurityEventService {

    private final SecurityEventRepository repository;

    public SecurityEventService(SecurityEventRepository repository) {
        this.repository = repository;
    }

    @Async
    public void log(UUID userId, String eventType, String ip, String userAgent, String details, String severity) {
        SecurityEvent event = new SecurityEvent();
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setIpAddress(ip == null ? "unknown" : ip);
        event.setUserAgent(userAgent == null ? "" : userAgent);
        event.setDetails(details);
        event.setSeverity(severity);
        repository.save(event);
    }
}
