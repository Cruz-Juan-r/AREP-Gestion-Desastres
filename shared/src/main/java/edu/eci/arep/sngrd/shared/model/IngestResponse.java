package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/** Cloud-only baseline: the device sends a reading and gets the alert (or null) back. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({"event_id", "duplicated", "alert"})
@JsonInclude(JsonInclude.Include.ALWAYS)
public record IngestResponse(String eventId, boolean duplicated, Alert alert) {
}
