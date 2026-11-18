package edu.eci.arep.sngrd.edge.store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verification of the hash chain and detection of tampering (hypothesis H5). */
class AuditLogTest {

    @TempDir
    Path dir;
    private SqliteFixture db;
    private AuditLog audit;

    @BeforeEach
    void setUp() {
        db = new SqliteFixture(dir);
        audit = new AuditLog(db.jdbc);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private void appendEntries(int n) {
        for (int i = 1; i <= n; i++) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("severity", i % 2 == 0 ? "critical" : "warning");
            detail.put("value", 4.5 + i / 10.0);
            detail.put("event_id", "e-" + i);
            audit.append(1_700_000_000_000L + i, "alert", "a-" + i, detail);
        }
    }

    @Test
    void digestMatchesPythonReference() {
        // Value computed with common/auditlog.py::_digest (CPython 3.13)
        String detail = "{\"device_id\":\"Mocoa-\\u00f1-01\",\"event_id\":\"e-1\",\"rule\":\"threshold\","
                + "\"severity\":\"critical\",\"value\":4.62}";
        assertEquals("c5ca61bc1752c4505ea20787ab756bbd777248586e6b2ee66b09d1f274b179d9",
                AuditLog.digest(AuditLog.GENESIS, 1_700_000_000_000L, "alert", "a-1", detail));
    }

    @Test
    void storesDetailAsSortedCompactJson() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("value", 4.62);
        detail.put("device_id", "Mocoa-ñ-01");
        audit.append(1L, "alert", "a-1", detail);
        assertEquals("{\"device_id\":\"Mocoa-\\u00f1-01\",\"value\":4.62}",
                db.jdbc.queryForObject("SELECT detail FROM audit WHERE seq = 1", String.class));
    }

    @Test
    void emptyChainIsValid() {
        Map<String, Object> v = audit.verify();
        assertEquals(true, v.get("valid"));
        assertEquals(0, v.get("entries"));
        assertEquals(AuditLog.GENESIS, v.get("head"));
    }

    @Test
    void chainStartsAtGenesisAndLinksEveryEntry() {
        appendEntries(5);
        List<Map<String, Object>> rows = db.jdbc.queryForList("SELECT prev_hash, hash FROM audit ORDER BY seq");
        assertEquals(AuditLog.GENESIS, rows.get(0).get("prev_hash"));
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1).get("hash"), rows.get(i).get("prev_hash"));
        }
        Map<String, Object> v = audit.verify();
        assertEquals(true, v.get("valid"));
        assertEquals(5, v.get("entries"));
        assertNull(v.get("first_invalid_seq"));
        assertEquals(rows.get(4).get("hash"), v.get("head"));
        assertEquals(List.of("valid", "entries", "first_invalid_seq", "head"), List.copyOf(v.keySet()));
    }

    @Test
    void tamperingAPastEntryIsLocated() {
        appendEntries(9);
        // Same alteration as experiment/run_experiment.py::tamper_test
        db.jdbc.update("UPDATE audit SET detail = replace(detail, '\"critical\"', '\"warning\"') || ' ' WHERE seq = ?", 4);
        Map<String, Object> v = audit.verify();
        assertFalse((Boolean) v.get("valid"));
        assertEquals(4L, v.get("first_invalid_seq"));
        assertEquals(4, v.get("entries"));
        assertFalse(v.containsKey("head"));
    }

    @Test
    void recomputingTheTamperedHashStillBreaksTheNextLink() {
        appendEntries(6);
        String forged = "{\"event_id\":\"e-3\",\"severity\":\"warning\",\"value\":1.0}";
        String prev = db.jdbc.queryForObject("SELECT prev_hash FROM audit WHERE seq = 3", String.class);
        long ts = db.jdbc.queryForObject("SELECT ts_ms FROM audit WHERE seq = 3", Long.class);
        db.jdbc.update("UPDATE audit SET detail = ?, hash = ? WHERE seq = 3",
                forged, AuditLog.digest(prev, ts, "alert", "a-3", forged));
        assertEquals(4L, audit.verify().get("first_invalid_seq"));
    }

    @Test
    void deletingAnEntryIsDetected() {
        appendEntries(6);
        db.jdbc.update("DELETE FROM audit WHERE seq = 2");
        Map<String, Object> v = audit.verify();
        assertFalse((Boolean) v.get("valid"));
        assertEquals(3L, v.get("first_invalid_seq"));
    }

    @Test
    void appendAfterReopeningContinuesTheChain() {
        appendEntries(3);
        AuditLog reopened = new AuditLog(db.jdbc);
        reopened.append(5L, "link_down", "cloud", Map.of("error", "ConnectException: refused"));
        assertTrue((Boolean) reopened.verify().get("valid"));
        assertEquals(4, reopened.verify().get("entries"));
    }
}
