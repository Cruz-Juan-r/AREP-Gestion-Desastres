package edu.eci.arep.sngrd.edge.node;

import edu.eci.arep.sngrd.edge.config.EdgeProperties;
import edu.eci.arep.sngrd.edge.store.EdgeStore;
import edu.eci.arep.sngrd.edge.sync.SyncAgent;
import edu.eci.arep.sngrd.shared.detect.Detector;
import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.ContractViolation;
import edu.eci.arep.sngrd.shared.model.Contracts;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Core of the edge node: validates each reading against the Telemetry contract,
 * runs the detector and stores reading + alert atomically in the outbox. Duplicates
 * (QoS 1 redeliveries) are recognised by {@code event_id}. Also owns the counters
 * exposed by {@code /metrics}.
 */
@Service
public class EdgeNode {

    /** Result of ingesting one message. {@code alert} is non-null only for stored readings. */
    public record Ingested(Status status, Alert alert) {
        public enum Status { STORED, DUPLICATE, INVALID }
    }

    private final EdgeProperties props;
    private final EdgeStore store;
    private final SyncAgent sync;
    private final Detector detector = new Detector("edge");
    private final ReentrantLock lock = new ReentrantLock();      // detector state is per device
    private final ProcessingStats processing = new ProcessingStats(20_000);
    private final AtomicLong received = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong invalid = new AtomicLong();
    private final long startedMs = System.currentTimeMillis();
    private volatile boolean mqttConnected;

    public EdgeNode(EdgeProperties props, EdgeStore store, SyncAgent sync) {
        this.props = props;
        this.store = store;
        this.sync = sync;
    }

    public Ingested ingest(byte[] payload) {
        Telemetry reading;
        try {
            reading = Contracts.telemetry(payload);
        } catch (ContractViolation e) {
            invalid.incrementAndGet();
            return new Ingested(Ingested.Status.INVALID, null);
        }
        Alert alert;
        lock.lock();
        try {
            alert = detector.evaluate(reading);
        } finally {
            lock.unlock();
        }
        if (!store.save(reading, alert)) {
            duplicates.incrementAndGet();
            return new Ingested(Ingested.Status.DUPLICATE, null);
        }
        received.incrementAndGet();
        return new Ingested(Ingested.Status.STORED, alert);
    }

    public void recordProcessingMs(double ms) {
        processing.add(ms);
    }

    public void setMqttConnected(boolean connected) {
        this.mqttConnected = connected;
    }

    public boolean isMqttConnected() {
        return mqttConnected;
    }

    public String nodeId() {
        return props.nodeId();
    }

    public Map<String, Object> metrics() {
        double[] p = processing.sorted();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("node_id", props.nodeId());
        m.put("uptime_s", ProcessingStats.round((System.currentTimeMillis() - startedMs) / 1000.0, 1));
        m.put("mqtt_connected", mqttConnected);
        m.put("readings_processed", received.get());
        m.put("duplicates_ignored", duplicates.get());
        m.put("invalid_messages", invalid.get());
        m.put("processing_ms_p50", ProcessingStats.percentile(p, 0.50));
        m.put("processing_ms_p95", ProcessingStats.percentile(p, 0.95));
        m.put("processing_ms_p99", ProcessingStats.percentile(p, 0.99));
        m.put("store", store.counts());
        m.put("sync", sync.status());
        return m;
    }

    /** Clears local state between experiment runs. */
    public void reset() {
        lock.lock();
        try {
            store.reset();
            detector.reset();
            processing.clear();
            received.set(0);
            duplicates.set(0);
            invalid.set(0);
            sync.resetCounters();
        } finally {
            lock.unlock();
        }
    }
}
