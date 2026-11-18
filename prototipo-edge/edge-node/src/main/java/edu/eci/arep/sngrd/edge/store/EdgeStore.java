package edu.eci.arep.sngrd.edge.store;

import edu.eci.arep.sngrd.shared.json.Json;
import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local durable store of the edge node (SQLite in WAL mode).
 *
 * <p>Implements the outbox pattern: every reading and alert is committed locally
 * <em>before</em> any attempt to send it to the cloud. The {@code synced} flag marks
 * what the cloud has already acknowledged, so a network outage never loses data; it
 * only grows the backlog.
 */
@Component
public class EdgeStore {

    private static final List<String> SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS events (
                event_id    TEXT PRIMARY KEY,
                device_id   TEXT NOT NULL,
                sensor_type TEXT NOT NULL,
                ts_ms       INTEGER NOT NULL,
                received_ms INTEGER NOT NULL,
                payload     TEXT NOT NULL,
                synced      INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_events_pending ON events(synced, received_ms)",
            """
            CREATE TABLE IF NOT EXISTS alerts (
                alert_id    TEXT PRIMARY KEY,
                event_id    TEXT NOT NULL,
                severity    TEXT NOT NULL,
                ts_alert_ms INTEGER NOT NULL,
                payload     TEXT NOT NULL,
                synced      INTEGER NOT NULL DEFAULT 0
            )""",
            "CREATE INDEX IF NOT EXISTS idx_alerts_pending ON alerts(synced, ts_alert_ms)");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditLog audit;

    public EdgeStore(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        SCHEMA.forEach(jdbc::execute);
        this.audit = new AuditLog(jdbc);
    }

    // ------------------------------------------------------------------ writes

    /** Persists a reading (and its alert) atomically. Returns false on duplicate. */
    public boolean save(Telemetry t, Alert alert) {
        long received = System.currentTimeMillis();
        Boolean stored = tx.execute(status -> {
            int inserted = jdbc.update(
                    "INSERT OR IGNORE INTO events (event_id, device_id, sensor_type, ts_ms, received_ms, payload) "
                            + "VALUES (?,?,?,?,?,?)",
                    t.eventId(), t.deviceId(), t.sensorType(), t.tsMs(), received, Json.write(t));
            if (inserted == 0) {                           // MQTT QoS 1 redelivery
                status.setRollbackOnly();
                return false;
            }
            if (alert != null) {
                jdbc.update("INSERT INTO alerts (alert_id, event_id, severity, ts_alert_ms, payload) VALUES (?,?,?,?,?)",
                        alert.alertId(), alert.eventId(), alert.severity(), alert.tsAlertMs(), Json.write(alert));
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("event_id", alert.eventId());
                detail.put("device_id", alert.deviceId());
                detail.put("severity", alert.severity());
                detail.put("rule", alert.rule());
                detail.put("value", alert.value());
                audit.append(alert.tsAlertMs(), "alert", alert.alertId(), detail);
            }
            return true;
        });
        return Boolean.TRUE.equals(stored);
    }

    public void markSynced(List<String> eventIds, List<String> alertIds, String batchId, Map<String, Object> ack) {
        tx.executeWithoutResult(status -> {
            jdbc.batchUpdate("UPDATE events SET synced=1 WHERE event_id=?", asArgs(eventIds));
            jdbc.batchUpdate("UPDATE alerts SET synced=1 WHERE alert_id=?", asArgs(alertIds));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("events", eventIds.size());
            detail.put("alerts", alertIds.size());
            detail.put("ack", ack);
            audit.append(System.currentTimeMillis(), "sync", batchId, detail);
        });
    }

    public void auditLinkChange(boolean up, Map<String, Object> detail) {
        tx.executeWithoutResult(status ->
                audit.append(System.currentTimeMillis(), up ? "link_up" : "link_down", "cloud", detail));
    }

    public void reset() {
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM events");
            jdbc.update("DELETE FROM alerts");
            jdbc.update("DELETE FROM audit");
            jdbc.update("DELETE FROM sqlite_sequence WHERE name='audit'");
        });
    }

    private static List<Object[]> asArgs(List<String> ids) {
        List<Object[]> args = new ArrayList<>(ids.size());
        ids.forEach(id -> args.add(new Object[]{id}));
        return args;
    }

    // ------------------------------------------------------------------- reads

    public Pending pending(int maxEvents, int maxAlerts) {
        List<String> eventIds = new ArrayList<>();
        List<String> eventPayloads = new ArrayList<>();
        List<String> alertIds = new ArrayList<>();
        List<String> alertPayloads = new ArrayList<>();
        jdbc.query("SELECT event_id, payload FROM events WHERE synced=0 ORDER BY received_ms LIMIT ?",
                rs -> {
                    eventIds.add(rs.getString(1));
                    eventPayloads.add(rs.getString(2));
                }, maxEvents);
        jdbc.query("SELECT alert_id, payload FROM alerts WHERE synced=0 ORDER BY ts_alert_ms LIMIT ?",
                rs -> {
                    alertIds.add(rs.getString(1));
                    alertPayloads.add(rs.getString(2));
                }, maxAlerts);
        return new Pending(eventIds, eventPayloads, alertIds, alertPayloads);
    }

    public Map<String, Object> counts() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("events_total", count("SELECT COUNT(*) FROM events"));
        c.put("events_pending", count("SELECT COUNT(*) FROM events WHERE synced=0"));
        c.put("alerts_total", count("SELECT COUNT(*) FROM alerts"));
        c.put("alerts_pending", count("SELECT COUNT(*) FROM alerts WHERE synced=0"));
        c.put("audit_entries", count("SELECT COUNT(*) FROM audit"));
        return c;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    public List<Map<String, Object>> recentAlerts(int limit) {
        return jdbc.query("SELECT payload, synced FROM alerts ORDER BY ts_alert_ms DESC LIMIT ?", (rs, i) -> {
            Map<String, Object> a = Json.readObject(rs.getString(1));
            a.put("synced", rs.getInt(2) != 0);
            return a;
        }, limit);
    }

    public Map<String, Object> verifyAudit() {
        return audit.verify();
    }
}
