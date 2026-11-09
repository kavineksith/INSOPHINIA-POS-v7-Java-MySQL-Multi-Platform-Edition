package com.insophinia.pos.service;

import com.insophinia.pos.repository.SystemLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Component
public class LogRetentionJob {
    private final SystemLogRepository logs; private final long retentionHours;
    public LogRetentionJob(SystemLogRepository logs, @Value("${app.logs.retention-hours:24}") long retentionHours) {
        this.logs = logs; this.retentionHours = Math.max(24, retentionHours);
    }
    @Scheduled(cron = "0 15 * * * *") @Transactional
    public void purgeExpiredLogs() { logs.deleteByCreatedAtBefore(Instant.now().minusSeconds(retentionHours * 3600L)); }
}
