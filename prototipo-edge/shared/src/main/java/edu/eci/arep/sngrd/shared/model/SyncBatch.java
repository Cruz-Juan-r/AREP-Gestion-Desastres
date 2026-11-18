package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/** Edge -> cloud synchronisation request ({@code POST /api/v1/sync}). */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({"node_id", "batch_id", "events", "alerts"})
public record SyncBatch(String nodeId, String batchId, List<Telemetry> events, List<Alert> alerts) {
}
