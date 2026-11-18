package edu.eci.arep.sngrd.edge.store;

import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outbox behaviour: deduplication, atomicity and synced marking. */
class EdgeStoreTest {

    @TempDir
    Path dir;
    private SqliteFixture db;

    @BeforeEach
    void setUp() {
        db = new SqliteFixture(dir);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    static Telemetry reading(String id, double value) {
        return new Telemetry(id, "rv-01", "river_level", value, "m", 1.15, -76.65, 1, 1_700_000_000_000L);
    }

    static Alert alertFor(Telemetry t, String alertId) {
        return new Alert(alertId, t.eventId(), t.deviceId(), t.sensorType(), "critical", "threshold",
                t.value(), 1.022, t.lat(), t.lon(), t.tsMs(), t.tsMs() + 2, "edge");
    }

    @Test
    void usesWalJournal() {
        assertEquals("wal", db.jdbc.queryForObject("PRAGMA journal_mode", String.class));
    }

    @Test
    void duplicateEventIdIsIgnored() {
        Telemetry t = reading("e-1", 2.0);
        assertTrue(db.store.save(t, null));
        assertFalse(db.store.save(t, alertFor(t, "a-dup")));       // QoS 1 redelivery
        Map<String, Object> c = db.store.counts();
        assertEquals(1L, c.get("events_total"));
        assertEquals(0L, c.get("alerts_total"));
        assertEquals(0L, c.get("audit_entries"));
    }

    @Test
    void readingAlertAndAuditAreCommittedTogether() {
        Telemetry t = reading("e-2", 4.6);
        assertTrue(db.store.save(t, alertFor(t, "a-2")));
        Map<String, Object> c = db.store.counts();
        assertEquals(1L, c.get("events_pending"));
        assertEquals(1L, c.get("alerts_pending"));
        assertEquals(1L, c.get("audit_entries"));
    }

    @Test
    void failedAlertInsertRollsBackTheReading() {
        Telemetry t1 = reading("e-3", 4.6);
        Telemetry t2 = reading("e-4", 4.7);
        db.store.save(t1, alertFor(t1, "a-same"));
        // alert_id collision makes the transaction fail: the reading must not stay behind
        assertThrows(RuntimeException.class, () -> db.store.save(t2, alertFor(t2, "a-same")));
        assertEquals(1L, db.store.counts().get("events_total"));
        assertEquals(1L, db.store.counts().get("audit_entries"));
    }

    @Test
    void onlyAcknowledgedRowsLeaveTheOutbox() {
        for (int i = 0; i < 5; i++) {
            Telemetry t = reading("e-" + i, 2.0);
            db.store.save(t, i == 2 ? alertFor(t, "a-" + i) : null);
        }
        Pending p = db.store.pending(3, 10);
        assertEquals(List.of("e-0", "e-1", "e-2"), p.eventIds());
        assertEquals(List.of("a-2"), p.alertIds());
        assertTrue(p.eventPayloads().get(0).startsWith("{\"event_id\":\"e-0\",\"device_id\":\"rv-01\""));

        db.store.markSynced(p.eventIds(), p.alertIds(), "b-1", Map.of("events_accepted", 3));
        Map<String, Object> c = db.store.counts();
        assertEquals(2L, c.get("events_pending"));
        assertEquals(0L, c.get("alerts_pending"));
        assertEquals(List.of("e-3", "e-4"), db.store.pending(10, 10).eventIds());
        assertEquals(true, db.store.recentAlerts(10).get(0).get("synced"));
        assertTrue((Boolean) db.store.verifyAudit().get("valid"));
    }

    @Test
    void resetClearsEverythingAndRestartsAuditSequence() {
        Telemetry t = reading("e-9", 4.9);
        db.store.save(t, alertFor(t, "a-9"));
        db.store.auditLinkChange(false, Map.of("error", "x"));
        db.store.reset();
        assertEquals(0L, db.store.counts().get("events_total"));
        db.store.auditLinkChange(true, Map.of("batch_id", "b"));
        assertEquals(1L, db.jdbc.queryForObject("SELECT seq FROM audit", Long.class));
        assertTrue((Boolean) db.store.verifyAudit().get("valid"));
    }
}
