package edu.eci.arep.sngrd.edge.sync;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.RawValue;
import edu.eci.arep.sngrd.edge.config.EdgeProperties;
import edu.eci.arep.sngrd.edge.store.EdgeStore;
import edu.eci.arep.sngrd.edge.store.Pending;
import edu.eci.arep.sngrd.shared.json.Json;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronisation agent: drains the local outbox towards the cloud.
 * <ul>
 *   <li>Batches of pending events/alerts are POSTed to {@code /api/v1/sync}.</li>
 *   <li>Rows are marked as synced only after the cloud acknowledges the batch, so a
 *       lost request or a lost acknowledgement just causes a resend.</li>
 *   <li>Resends are harmless because the cloud deduplicates by event_id/alert_id
 *       (idempotent receiver).</li>
 *   <li>Failures trigger exponential backoff (0.5 s -> 8 s) to avoid hammering a
 *       degraded link.</li>
 * </ul>
 */
@Component
public class SyncAgent implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(SyncAgent.class);

    private final EdgeStore store;
    private final String nodeId;
    private final URI syncUri;
    private final EdgeProperties.Sync cfg;
    private final HttpClient http;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private Thread thread;

    private volatile Boolean linkUp;               // null until the first attempt
    private volatile Long lastSuccessMs;
    private volatile String lastError;
    private final AtomicLong batchesOk = new AtomicLong();
    private final AtomicLong batchesFailed = new AtomicLong();
    private final AtomicLong eventsSent = new AtomicLong();
    private final AtomicLong eventsDupAcked = new AtomicLong();

    private final boolean autostart;

    public SyncAgent(EdgeStore store, EdgeProperties props,
                     @Value("${edge.autostart:true}") boolean autostart) {
        this.store = store;
        this.autostart = autostart;
        this.nodeId = props.nodeId();
        this.syncUri = URI.create(props.cloudUrl().replaceAll("/+$", "") + "/api/v1/sync");
        this.cfg = props.sync();
        // HTTP/1.1 explicitly: the default client would try an h2c upgrade through the WAN proxy.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(cfg.timeoutMs()))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!autostart) {
            return;
        }
        thread = new Thread(this, "sync-agent");
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void stop() {
        stopped.countDown();
    }

    /** Waits up to {@code ms}; returns true if the agent was asked to stop. */
    private boolean pause(long ms) {
        try {
            return stopped.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private void setLink(boolean up, Map<String, Object> detail) {
        if (linkUp == null || linkUp != up) {
            linkUp = up;
            store.auditLinkChange(up, detail);
            log.info("cloud link {} {}", up ? "UP" : "DOWN", detail);
        }
    }

    @Override
    public void run() {
        long backoff = cfg.backoffMinMs();
        while (stopped.getCount() > 0) {
            Pending p;
            try {
                p = store.pending(cfg.batchEvents(), cfg.batchAlerts());
            } catch (RuntimeException e) {
                log.warn("could not read the outbox: {}", e.toString());
                if (pause(cfg.idleMs())) {
                    return;
                }
                continue;
            }
            if (p.isEmpty()) {
                if (pause(cfg.idleMs())) {
                    return;
                }
                continue;
            }
            String batchId = UUID.randomUUID().toString();
            Map<String, Object> ack;
            try {
                HttpRequest req = HttpRequest.newBuilder(syncUri)
                        .timeout(Duration.ofMillis(cfg.timeoutMs()))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(batchJson(batchId, p)))
                        .build();
                HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) {
                    throw new IllegalStateException("HTTP " + r.statusCode());
                }
                ack = Json.readObject(r.body());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {                    // network error, timeout, 5xx, lost ack
                batchesFailed.incrementAndGet();
                String msg = e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage());
                lastError = msg.length() > 200 ? msg.substring(0, 200) : msg;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("error", lastError);
                setLink(false, detail);
                if (pause(backoff)) {
                    return;
                }
                backoff = Math.min(backoff * 2, cfg.backoffMaxMs());
                continue;
            }

            store.markSynced(p.eventIds(), p.alertIds(), batchId, ack);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("batch_id", batchId);
            setLink(true, detail);
            backoff = cfg.backoffMinMs();
            batchesOk.incrementAndGet();
            eventsSent.addAndGet(p.eventIds().size());
            if (ack.get("events_duplicated") instanceof Number n) {
                eventsDupAcked.addAndGet(n.longValue());
            }
            lastSuccessMs = System.currentTimeMillis();
            if (p.eventIds().size() < cfg.batchEvents() && p.alertIds().size() < cfg.batchAlerts()
                    && pause(cfg.idleMs())) {
                return;
            }
        }
    }

    /** SyncBatch JSON; stored payloads are embedded verbatim (they were validated on ingest). */
    private String batchJson(String batchId, Pending p) {
        ObjectNode root = Json.MAPPER.createObjectNode();
        root.put("node_id", nodeId);
        root.put("batch_id", batchId);
        ArrayNode events = root.putArray("events");
        p.eventPayloads().forEach(json -> events.addRawValue(new RawValue(json)));
        ArrayNode alerts = root.putArray("alerts");
        p.alertPayloads().forEach(json -> alerts.addRawValue(new RawValue(json)));
        return Json.write(root);
    }

    public Boolean linkUp() {
        return linkUp;
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("link_up", linkUp);
        s.put("last_success_ms", lastSuccessMs);
        s.put("last_error", lastError);
        s.put("batches_ok", batchesOk.get());
        s.put("batches_failed", batchesFailed.get());
        s.put("events_sent", eventsSent.get());
        s.put("events_duplicated_acked", eventsDupAcked.get());
        return s;
    }

    public void resetCounters() {
        batchesOk.set(0);
        batchesFailed.set(0);
        eventsSent.set(0);
        eventsDupAcked.set(0);
        lastError = null;
    }
}
