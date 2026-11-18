package edu.eci.arep.sngrd.cloud;

import com.fasterxml.jackson.databind.JsonNode;
import edu.eci.arep.sngrd.shared.model.Contracts;
import edu.eci.arep.sngrd.shared.model.IngestResponse;
import edu.eci.arep.sngrd.shared.model.SyncAck;
import edu.eci.arep.sngrd.shared.model.SyncBatch;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * HTTP API of the national cloud. Bodies are read as JSON trees and validated against
 * the contracts explicitly, so invalid input gets the same 422 shape as the Python API.
 */
@RestController
public class CloudController {

    private static final List<Object> BODY = List.of("body");

    private final CloudService service;

    public CloudController(CloudService service) {
        this.service = service;
    }

    @Operation(summary = "Sync", description = "Idempotent receiver of edge synchronisation batches.")
    @PostMapping("/api/v1/sync")
    public SyncAck sync(@io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = SyncBatch.class)))
                        @RequestBody JsonNode body) {
        return service.sync(Contracts.syncBatch(body, BODY));
    }

    @Operation(summary = "Ingest", description = "Cloud-only baseline: detection happens here, far from the field.")
    @PostMapping("/api/v1/ingest")
    public IngestResponse ingest(@io.swagger.v3.oas.annotations.parameters.RequestBody(
            content = @Content(schema = @Schema(implementation = Telemetry.class)))
                                 @RequestBody JsonNode body) {
        return service.ingest(Contracts.telemetry(body, BODY));
    }

    @Operation(summary = "Stats")
    @GetMapping("/api/v1/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    @Operation(summary = "Event Ids", description = "Used by the experiment to compare what was sent with what arrived.")
    @GetMapping("/api/v1/events/ids")
    public List<String> eventIds() {
        return service.eventIds();
    }

    @Operation(summary = "Health")
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok");
    }

    @Operation(summary = "Reset")
    @PostMapping("/admin/reset")
    public Map<String, Object> reset() {
        service.reset();
        return Map.of("reset", true);
    }

    @Operation(summary = "Set Fault")
    @PostMapping("/admin/fault")
    public Map<String, Object> setFault(@RequestParam("ack_loss") double ackLoss) {
        return Map.of("ack_loss", service.setFaultAckLoss(ackLoss));
    }
}
