package edu.eci.arep.sngrd.cloud;

import edu.eci.arep.sngrd.shared.detect.Detector;
import edu.eci.arep.sngrd.shared.json.Json;
import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.IngestResponse;
import edu.eci.arep.sngrd.shared.model.SyncAck;
import edu.eci.arep.sngrd.shared.model.SyncBatch;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Central store and logic of the cloud API. Idempotency comes from the primary keys:
 * {@code INSERT OR IGNORE} by {@code event_id}/{@code alert_id} makes a resent batch a
 * no-op, and the number of ignored rows is reported back as duplicates.
 */
@Service
public class CloudService {

    private static final List<String> SCHEMA = List.of("""
            CREATE TABLE IF NOT EXISTS events (
                event_id    TEXT PRIMARY KEY,
                origin      TEXT NOT NULL,          -- edge node id, or 'direct' for the baseline
                device_id   TEXT NOT NULL,
                sensor_type TEXT NOT NULL,
                ts_ms       INTEGER NOT NULL,
                stored_ms   INTEGER NOT NULL,
                payload     TEXT NOT NULL
            )""", """
            CREATE TABLE IF NOT EXISTS alerts (
                alert_id    TEXT PRIMARY KEY,
                origin      TEXT NOT NULL,
                event_id    TEXT NOT NULL,
                severity    TEXT NOT NULL,
                stored_ms   INTEGER NOT NULL,
                payload     TEXT NOT NULL
            )""");

    private static final List<String> COUNTERS = List.of(
            "sync_batches", "events_duplicated", "alerts_duplicated", "acks_dropped", "ingest_requests");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();
    private final Detector detector = new Detector("cloud");
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private volatile double faultAckLoss;

    public CloudService(JdbcTemplate jdbc, PlatformTransactionManager txManager, CloudProperties props) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.faultAckLoss = props.faultAckLoss();
        SCHEMA.forEach(jdbc::execute);
        COUNTERS.forEach(k -> counters.put(k, 0L));
    }

    /** Idempotent receiver of edge batches. Throws {@link AckLostException} on injected faults. */
    public SyncAck sync(SyncBatch batch) {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            int[] ok = tx.execute(status -> new int[]{
                    insertEvents(batch.events(), batch.nodeId(), now),
                    insertAlerts(batch.alerts(), batch.nodeId(), now)});
            int evOk = ok[0];
            int alOk = ok[1];
            add("sync_batches", 1);
            add("events_duplicated", batch.events().size() - evOk);
            add("alerts_duplicated", batch.alerts().size() - alOk);
            double p = faultAckLoss;
            if (p > 0 && ThreadLocalRandom.current().nextDouble() < p) {
                add("acks_dropped", 1);
                // Data is committed but the edge never learns it: it must resend.
                throw new AckLostException();
            }
            return new SyncAck(batch.batchId(), evOk, batch.events().size() - evOk,
                    alOk, batch.alerts().size() - alOk);
        } finally {
            lock.unlock();
        }
    }

    /** Cloud-only baseline: detection happens here, far from the field. */
    public IngestResponse ingest(Telemetry t) {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            add("ingest_requests", 1);
            return tx.execute(status -> {
                boolean inserted = insertEvents(List.of(t), "direct", now) == 1;
                Alert a = inserted ? detector.evaluate(t) : null;
                if (a != null) {
                    jdbc.update("INSERT INTO alerts VALUES (?,?,?,?,?,?)",
                            a.alertId(), "direct", a.eventId(), a.severity(), now, Json.write(a));
                }
                return new IngestResponse(t.eventId(), !inserted, a);
            });
        } finally {
            lock.unlock();
        }
    }

    private int insertEvents(List<Telemetry> events, String origin, long now) {
        if (events.isEmpty()) {
            return 0;
        }
        Integer n = jdbc.execute((ConnectionCallback<Integer>) con -> {
            int ok = 0;
            try (PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO events VALUES (?,?,?,?,?,?,?)")) {
                for (Telemetry e : events) {
                    ps.setString(1, e.eventId());
                    ps.setString(2, origin);
                    ps.setString(3, e.deviceId());
                    ps.setString(4, e.sensorType());
                    ps.setLong(5, e.tsMs());
                    ps.setLong(6, now);
                    ps.setString(7, Json.write(e));
                    ok += ps.executeUpdate();
                }
            }
            return ok;
        });
        return n == null ? 0 : n;
    }

    private int insertAlerts(List<Alert> alerts, String origin, long now) {
        if (alerts.isEmpty()) {
            return 0;
        }
        Integer n = jdbc.execute((ConnectionCallback<Integer>) con -> {
            int ok = 0;
            try (PreparedStatement ps = con.prepareStatement("INSERT OR IGNORE INTO alerts VALUES (?,?,?,?,?,?)")) {
                for (Alert a : alerts) {
                    ps.setString(1, a.alertId());
                    ps.setString(2, origin);
                    ps.setString(3, a.eventId());
                    ps.setString(4, a.severity());
                    ps.setLong(5, now);
                    ps.setString(6, Json.write(a));
                    ok += ps.executeUpdate();
                }
            }
            return ok;
        });
        return n == null ? 0 : n;
    }

    private void add(String counter, long delta) {
        counters.merge(counter, delta, Long::sum);
    }

    public Map<String, Object> stats() {
        lock.lock();
        try {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("events_stored", jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class));
            s.put("events_distinct", jdbc.queryForObject("SELECT COUNT(DISTINCT event_id) FROM events", Long.class));
            s.put("alerts_stored", jdbc.queryForObject("SELECT COUNT(*) FROM alerts", Long.class));
            Map<String, Object> byOrigin = new LinkedHashMap<>();
            jdbc.query("SELECT origin, COUNT(*) FROM events GROUP BY origin",
                    rs -> {
                        byOrigin.put(rs.getString(1), rs.getLong(2));
                    });
            s.put("by_origin", byOrigin);
            s.putAll(counters);
            return s;
        } finally {
            lock.unlock();
        }
    }

    public List<String> eventIds() {
        lock.lock();
        try {
            return jdbc.queryForList("SELECT event_id FROM events", String.class);
        } finally {
            lock.unlock();
        }
    }

    public void reset() {
        lock.lock();
        try {
            tx.executeWithoutResult(status -> {
                jdbc.update("DELETE FROM events");
                jdbc.update("DELETE FROM alerts");
            });
            detector.reset();
            COUNTERS.forEach(k -> counters.put(k, 0L));
        } finally {
            lock.unlock();
        }
    }

    public double setFaultAckLoss(double ackLoss) {
        if (!(ackLoss >= 0 && ackLoss <= 1)) {
            throw new IllegalArgumentException("ack_loss must be in [0, 1]");
        }
        faultAckLoss = ackLoss;
        return faultAckLoss;
    }
}
