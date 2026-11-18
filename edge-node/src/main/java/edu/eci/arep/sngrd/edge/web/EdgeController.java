package edu.eci.arep.sngrd.edge.web;

import edu.eci.arep.sngrd.edge.node.EdgeNode;
import edu.eci.arep.sngrd.edge.sse.AlertBroadcaster;
import edu.eci.arep.sngrd.edge.store.EdgeStore;
import edu.eci.arep.sngrd.edge.sync.SyncAgent;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP API of the edge node. The local COP dashboard is served from static/index.html at "/". */
@RestController
public class EdgeController {

    private final EdgeNode node;
    private final EdgeStore store;
    private final SyncAgent sync;
    private final AlertBroadcaster broadcaster;

    public EdgeController(EdgeNode node, EdgeStore store, SyncAgent sync, AlertBroadcaster broadcaster) {
        this.node = node;
        this.store = store;
        this.sync = sync;
        this.broadcaster = broadcaster;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("status", "ok");
        h.put("node_id", node.nodeId());
        h.put("mqtt_connected", node.isMqttConnected());
        h.put("cloud_link_up", sync.linkUp());
        return h;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return node.metrics();
    }

    @GetMapping("/alerts/recent")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "50") int limit) {
        return store.recentAlerts(limit);
    }

    /** Server-Sent Events: pushes every new alert to the local dashboard. */
    @GetMapping(path = "/alerts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return broadcaster.subscribe();
    }

    @GetMapping("/audit/verify")
    public Map<String, Object> auditVerify() {
        return store.verifyAudit();
    }

    /** Clears local state between experiment runs. */
    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        node.reset();
        return Map.of("reset", true);
    }
}
