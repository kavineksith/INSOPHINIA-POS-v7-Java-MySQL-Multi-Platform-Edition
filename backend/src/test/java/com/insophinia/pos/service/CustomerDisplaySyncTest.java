package com.insophinia.pos.service;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomerDisplaySyncTest {
    @Test
    void customerCartSnapshotsDoNotEnterGeneralSyncHistory() {
        SyncHub hub = new SyncHub();
        hub.broadcastDisplayCart("paired-channel-0123456789", Map.of("items", java.util.List.of(), "total", "0.00"));
        assertTrue(hub.eventsSince(null, null).isEmpty());
        assertTrue(hub.currentSeq() == 0);
    }
}
