package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * One reading published by a field device (MQTT topic {@code sensors/{type}/{device}}).
 * Mirrors {@code contracts/telemetry.schema.json}; {@code event_id} is the end-to-end
 * idempotency key.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({"event_id", "device_id", "sensor_type", "value", "unit", "lat", "lon", "seq", "ts_ms"})
public record Telemetry(
        String eventId,
        String deviceId,
        String sensorType,
        double value,
        String unit,
        double lat,
        double lon,
        long seq,
        long tsMs) {
}
