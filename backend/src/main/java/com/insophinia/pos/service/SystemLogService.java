package com.insophinia.pos.service;

import com.insophinia.pos.entity.SystemLog;
import com.insophinia.pos.repository.SystemLogRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class SystemLogService {

    private final SystemLogRepository repository;

    public SystemLogService(SystemLogRepository repository) {
        this.repository = repository;
    }

    @Async
    public void append(String level, String category, String action, String message,
                       String metadata, UUID userId, String ipAddress) {
        SystemLog log = new SystemLog();
        log.setLevel(level);
        log.setCategory(category);
        log.setAction(action);
        log.setMessage(message);
        log.setMetadata(metadata);
        log.setUserId(userId);
        log.setIpAddress(ipAddress);
        repository.save(log);
    }
}
