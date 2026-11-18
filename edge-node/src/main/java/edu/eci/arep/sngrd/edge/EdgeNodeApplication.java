package edu.eci.arep.sngrd.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Municipal edge node (PMU): real-time ingestion, detection, alerting and sync.
 *
 * <p>Environment (same variables as the Python prototype):
 * <pre>
 *   MQTT_HOST / MQTT_PORT   broker with field telemetry          (localhost:1883)
 *   CLOUD_URL               national cloud API, via the WAN link  (http://localhost:9000)
 *   EDGE_DB                 SQLite file                           (edge.db)
 *   NODE_ID                 edge node identifier                  (pmu-municipio-01)
 * </pre>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class EdgeNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdgeNodeApplication.class, args);
    }
}
