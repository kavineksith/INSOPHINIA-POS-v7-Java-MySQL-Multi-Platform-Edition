package com.insophinia.pos.service;

import com.insophinia.pos.dto.MiscDtos.SystemStatusResponse;
import com.insophinia.pos.entity.SystemState;
import com.insophinia.pos.exception.SystemLockedException;
import com.insophinia.pos.repository.SystemStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** System-wide deadman switch — port of /api/system/lock, /checkin, /status. */
@Service
public class SystemService {

    private final SystemStateRepository repository;

    public SystemService(SystemStateRepository repository) {
        this.repository = repository;
    }

    public SystemState state() {
        return repository.findAll().stream().findFirst().orElseGet(() -> repository.save(new SystemState()));
    }

    @Transactional
    public SystemStatusResponse status() {
        SystemState s = state();
        Instant deadline = s.getLastCheckinAt().plusSeconds(s.getDeadmanDays() * 86400L);
        if (!s.isLocked() && Instant.now().isAfter(deadline)) {
            s.setLocked(true);
            s.setLockReason("Automatic deadman lock: no check-in within " + s.getDeadmanDays() + " days");
            s.setLockedAt(Instant.now());
            repository.save(s);
        }
        return toResponse(s);
    }

    @Transactional
    public SystemStatusResponse setLocked(boolean locked, String reason, UUID by) {
        SystemState s = state();
        s.setLocked(locked);
        s.setLockReason(locked ? reason : null);
        s.setLockedBy(locked ? by : null);
        s.setLockedAt(locked ? Instant.now() : null);
        repository.save(s);
        return toResponse(s);
    }

    @Transactional
    public void checkin() {
        SystemState s = state();
        s.setLastCheckinAt(Instant.now());
        repository.save(s);
    }

    public void assertNotLocked() {
        SystemState s = state();
        if (s.isLocked()) {
            throw new SystemLockedException("System is locked: " +
                    (s.getLockReason() == null ? "contact your administrator" : s.getLockReason()));
        }
    }

    private static SystemStatusResponse toResponse(SystemState s) {
        return new SystemStatusResponse(s.isLocked(), s.getLockReason(), s.getLockedAt(),
                s.getLastCheckinAt(), s.getDeadmanDays());
    }
}
