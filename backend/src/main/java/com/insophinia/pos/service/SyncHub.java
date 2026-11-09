package com.insophinia.pos.service;

import com.insophinia.pos.dto.MiscDtos.SyncEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sync event hub.
 * Broadcasts mutation events to all connected POS terminals over SSE,
 * keeps a 200-event ring buffer for delta catch-up via /api/sync/changes.
 */
@Service
public class SyncHub {

    private static final int MAX_HISTORY = 200;

    private final Map<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, SseEmitter>> displayClients = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> displayLatest = new ConcurrentHashMap<>();
    private final List<SyncEvent> history = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong(0);

    public SseEmitter registerDisplay(String clientId, String channel) {
        SseEmitter emitter = new SseEmitter(0L);
        displayClients.computeIfAbsent(channel, k -> new ConcurrentHashMap<>()).put(clientId, emitter);
        Runnable remove = () -> {
            Map<String, SseEmitter> group = displayClients.get(channel);
            if (group != null) { group.remove(clientId); if (group.isEmpty()) displayClients.remove(channel, group); }
        };
        emitter.onCompletion(remove); emitter.onTimeout(remove); emitter.onError(e -> remove.run());
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("channel", "customer-display")));
            Map<String, Object> latest = displayLatest.get(channel);
            if (latest != null) emitter.send(SseEmitter.event().name("cart").data(latest));
        } catch (IOException e) { remove.run(); }
        return emitter;
    }

    /** Cart snapshots bypass the general sync stream and event history; only paired displays receive them. */
    public void broadcastDisplayCart(String channel, Map<String, Object> cart) {
        displayLatest.put(channel, Map.copyOf(cart)); // in-memory only; never added to durable event history
        Map<String, SseEmitter> group = displayClients.get(channel);
        if (group == null) return;
        for (Map.Entry<String, SseEmitter> entry : group.entrySet()) {
            try { entry.getValue().send(SseEmitter.event().name("cart").data(cart)); }
            catch (Exception e) { group.remove(entry.getKey()); }
        }
    }

    public SseEmitter register(String clientId) {
        SseEmitter emitter = new SseEmitter(0L); // no timeout; heartbeat keeps alive
        clients.put(clientId, emitter);
        emitter.onCompletion(() -> clients.remove(clientId));
        emitter.onTimeout(() -> clients.remove(clientId));
        emitter.onError(e -> clients.remove(clientId));
        try {
            emitter.send(SseEmitter.event().name("connected").data(Map.of("clientId", clientId)));
        } catch (IOException e) {
            clients.remove(clientId);
        }
        return emitter;
    }

    public void broadcast(String type, String entity, Map<String, Object> data) {
        SyncEvent event = new SyncEvent(seq.incrementAndGet(), type, entity, Instant.now(), data);
        history.add(event);
        while (history.size() > MAX_HISTORY) history.remove(0);

        for (Map.Entry<String, SseEmitter> entry : clients.entrySet()) {
            try {
                entry.getValue().send(SseEmitter.event().name("sync").data(event));
            } catch (Exception e) {
                clients.remove(entry.getKey());
            }
        }
    }

    public List<SyncEvent> eventsSince(Long lastSeq, Instant since) {
        List<SyncEvent> result = new ArrayList<>();
        for (SyncEvent e : history) {
            if (lastSeq != null && e.seq() <= lastSeq) continue;
            if (lastSeq == null && since != null && e.timestamp().isBefore(since)) continue;
            result.add(e);
        }
        return result;
    }

    public long currentSeq() { return seq.get(); }

    /**
     * Snapshot of active customer-display channels + their paired client counts.
     * Used by the admin endpoint GET /api/sync/customer-display/channels so
     * operators can audit which terminals are pairing with which displays.
     *
     * Returns a defensive copy so callers can't mutate the internal maps.
     */
    public Map<String, Integer> displayChannelSnapshot() {
        Map<String, Integer> result = new java.util.LinkedHashMap<>();
        displayClients.forEach((channel, group) -> result.put(channel, group.size()));
        return java.util.Collections.unmodifiableMap(result);
    }

    /**
     * Total number of POS terminals connected to the general sync stream
     * (not customer-display clients). Used by the admin status panel.
     */
    public int generalClientCount() {
        return clients.size();
    }

    @Scheduled(fixedRate = 25_000) // 25s keepalive, same as the the API implementation
    public void heartbeat() {
        clients.forEach((id, emitter) -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                clients.remove(id);
            }
        });
        displayClients.forEach((channel, group) -> group.forEach((id, emitter) -> {
            try { emitter.send(SseEmitter.event().comment("ping")); }
            catch (Exception e) { group.remove(id); }
        }));
    }

    public static String newClientId(UUID userId) {
        return "pos_" + userId + "_" + UUID.randomUUID();
    }
}
