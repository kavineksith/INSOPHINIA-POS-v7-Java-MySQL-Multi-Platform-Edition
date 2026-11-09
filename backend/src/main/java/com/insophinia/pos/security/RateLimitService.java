package com.insophinia.pos.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Simple in-memory sliding-window rate limiter, keyed per IP + bucket (e.g. "login"). */
@Service
public class RateLimitService {

    private record Window(AtomicInteger count, long windowStartMillis) {}

    private final Map<String, Window> buckets = new ConcurrentHashMap<>();
    private final int loginMax;
    private final int apiMax;
    private final long windowMillis;

    public RateLimitService(@Value("${app.rate-limit.login-max:10}") int loginMax,
                            @Value("${app.rate-limit.api-max:300}") int apiMax,
                            @Value("${app.rate-limit.window-seconds:60}") long windowSeconds) {
        this.loginMax = loginMax;
        this.apiMax = apiMax;
        this.windowMillis = windowSeconds * 1000;
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${app.rate-limit.cleanup-ms:60000}")
    public void cleanupExpiredWindows() {
        long cutoff = Instant.now().toEpochMilli() - (windowMillis * 2);
        buckets.entrySet().removeIf(entry -> entry.getValue().windowStartMillis() < cutoff);
    }

    public boolean allow(String ip, String bucket) {
        int max = "login".equals(bucket) ? loginMax : apiMax;
        String key = bucket + ":" + ip;
        long now = Instant.now().toEpochMilli();
        Window w = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis() > windowMillis) {
                return new Window(new AtomicInteger(0), now);
            }
            return existing;
        });
        return w.count().incrementAndGet() <= max;
    }
}
