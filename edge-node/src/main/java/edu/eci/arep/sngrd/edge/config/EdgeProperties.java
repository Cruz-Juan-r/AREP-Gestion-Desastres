package edu.eci.arep.sngrd.edge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge node settings, bound from {@code application.yml} (which maps the environment
 * variables NODE_ID, MQTT_HOST, MQTT_PORT, CLOUD_URL and EDGE_DB).
 */
@ConfigurationProperties(prefix = "edge")
public record EdgeProperties(
        String nodeId,
        String mqttHost,
        int mqttPort,
        String cloudUrl,
        String db,
        Sync sync) {

    /** Synchronisation agent tuning; defaults equal the Python SyncAgent. */
    public record Sync(int batchEvents, int batchAlerts, long idleMs, long backoffMinMs,
                       long backoffMaxMs, long timeoutMs) {
    }
}
