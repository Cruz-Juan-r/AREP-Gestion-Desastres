package edu.eci.arep.sngrd.cloud;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * National cloud API (prototype of the central level).
 *
 * <p>Two roles:
 * <ul>
 *   <li>Idempotent receiver of edge synchronisation batches ({@code POST /api/v1/sync}).</li>
 *   <li>Cloud-only baseline: devices send each reading directly and get the alert back
 *       in the response ({@code POST /api/v1/ingest}). This is the "no edge" design the
 *       experiment compares against.</li>
 * </ul>
 * Environment: {@code CLOUD_DB} (SQLite file, default cloud.db) and {@code FAULT_ACK_LOSS}
 * (probability in [0, 1] of committing a sync batch and then answering 503, i.e. a lost
 * acknowledgement; default 0).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(info = @Info(title = "Nube nacional - prototipo", version = "0.1.0"))
public class CloudApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(CloudApiApplication.class, args);
    }
}
