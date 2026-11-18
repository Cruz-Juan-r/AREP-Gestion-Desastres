package edu.eci.arep.sngrd.shared.model;

import edu.eci.arep.sngrd.shared.json.Json;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContractsTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void parsesValidTelemetryAndKeepsWireFormat() {
        String json = "{\"event_id\":\"e-1\",\"device_id\":\"rv-01\",\"sensor_type\":\"river_level\","
                + "\"value\":2.31,\"unit\":\"m\",\"lat\":1.15,\"lon\":-76.65,\"seq\":7,\"ts_ms\":1700000000000}";
        Telemetry t = Contracts.telemetry(bytes(json));
        assertEquals("rv-01", t.deviceId());
        assertEquals(7, t.seq());
        assertEquals(json, Json.write(t));
    }

    @Test
    void missingEventIdGetsUuidAndIntegersAreAcceptedAsNumbers() {
        Telemetry t = Contracts.telemetry(bytes("{\"device_id\":\"d\",\"sensor_type\":\"rainfall\",\"value\":5,"
                + "\"unit\":\"mm/h\",\"lat\":1,\"lon\":2,\"seq\":3.0,\"ts_ms\":10}"));
        assertEquals(36, UUID.fromString(t.eventId()).toString().length());
        assertEquals(5.0, t.value());
        assertEquals(3, t.seq());
    }

    @Test
    void rejectsInvalidMessages() {
        assertThrows(ContractViolation.class, () -> Contracts.telemetry(bytes("not json")));
        assertThrows(ContractViolation.class, () -> Contracts.telemetry(bytes("[1,2]")));
        ContractViolation v = assertThrows(ContractViolation.class, () -> Contracts.telemetry(bytes(
                "{\"device_id\":5,\"sensor_type\":\"volcano\",\"value\":\"x\",\"unit\":\"m\","
                        + "\"lat\":1,\"lon\":2,\"seq\":-1}")));
        assertEquals(5, v.errors().size());   // device_id, sensor_type, value, seq, ts_ms
        assertTrue(v.errors().stream().anyMatch(e -> "missing".equals(e.get("type"))));
        assertTrue(v.errors().stream().anyMatch(e -> "greater_than_equal".equals(e.get("type"))));
    }
}
