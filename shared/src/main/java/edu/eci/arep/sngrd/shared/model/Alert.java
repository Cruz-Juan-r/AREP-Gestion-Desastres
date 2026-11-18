package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

/**
 * Alert produced by the detector (MQTT topic {@code alerts/{severity}/{device}}).
 * Mirrors {@code contracts/alert.schema.json}.
 *
 * @param score threshold ratio or |z| that triggered the alert
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@JsonPropertyOrder({"alert_id", "event_id", "device_id", "sensor_type", "severity", "rule", "value",
        "score", "lat", "lon", "ts_event_ms", "ts_alert_ms", "source"})
public record Alert(
        String alertId,
        String eventId,
        String deviceId,
        String sensorType,
        String severity,
        String rule,
        double value,
        double score,
        double lat,
        double lon,
        long tsEventMs,
        long tsAlertMs,
        String source) {
}
