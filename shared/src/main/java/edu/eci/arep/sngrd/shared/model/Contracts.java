package edu.eci.arep.sngrd.shared.model;

import com.fasterxml.jackson.databind.JsonNode;
import edu.eci.arep.sngrd.shared.json.Json;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime validation of the JSON contracts in {@code contracts/}.
 *
 * <p>Reproduces the (lax, JSON-mode) rules the Python prototype got from Pydantic:
 * missing {@code event_id}/{@code alert_id}/{@code batch_id} get a fresh UUID, numbers
 * accept integers and numeric strings, integers accept floats without fractional part,
 * strings must be JSON strings, enumerations are checked and unknown fields are ignored.
 */
public final class Contracts {

    public static final List<String> SENSOR_TYPES = List.of("river_level", "rainfall", "seismic", "drone");
    public static final List<String> SEVERITIES = List.of("warning", "critical");
    public static final List<String> RULES = List.of("threshold", "zscore");
    public static final List<String> SOURCES = List.of("edge", "cloud");

    private Contracts() {
    }

    /** Parses and validates a Telemetry message from raw bytes (e.g. an MQTT payload). */
    public static Telemetry telemetry(byte[] json) {
        JsonNode node;
        try {
            node = Json.MAPPER.readTree(json);
        } catch (IOException e) {
            throw new ContractViolation(List.of(error("json_invalid", List.of(), "Invalid JSON: " + e.getMessage(), null)));
        }
        return telemetry(node, List.of());
    }

    public static Telemetry telemetry(JsonNode node, List<Object> loc) {
        Fields f = new Fields(node, loc);
        Telemetry t = f.isObject() ? new Telemetry(
                f.str("event_id", true),
                f.str("device_id", false),
                f.literal("sensor_type", SENSOR_TYPES),
                f.number("value"),
                f.str("unit", false),
                f.number("lat"),
                f.number("lon"),
                f.integer("seq", 0L),
                f.integer("ts_ms", null)) : null;
        f.throwIfInvalid();
        return t;
    }

    public static Alert alert(JsonNode node, List<Object> loc) {
        Fields f = new Fields(node, loc);
        Alert a = f.isObject() ? new Alert(
                f.str("alert_id", true),
                f.str("event_id", false),
                f.str("device_id", false),
                f.literal("sensor_type", SENSOR_TYPES),
                f.literal("severity", SEVERITIES),
                f.literal("rule", RULES),
                f.number("value"),
                f.number("score"),
                f.number("lat"),
                f.number("lon"),
                f.integer("ts_event_ms", null),
                f.integer("ts_alert_ms", null),
                f.literal("source", SOURCES)) : null;
        f.throwIfInvalid();
        return a;
    }

    /** Validates a whole batch, collecting the errors of every nested event and alert. */
    public static SyncBatch syncBatch(JsonNode node, List<Object> loc) {
        Fields f = new Fields(node, loc);
        if (!f.isObject()) {
            f.throwIfInvalid();
        }
        String nodeId = f.str("node_id", false);
        String batchId = f.str("batch_id", true);
        List<Telemetry> events = new ArrayList<>();
        List<Alert> alerts = new ArrayList<>();
        for (JsonNode item : f.list("events")) {
            List<Object> l = append(loc, "events", events.size());
            try {
                events.add(telemetry(item, l));
            } catch (ContractViolation v) {
                f.errors.addAll(v.errors());
                events.add(null);
            }
        }
        for (JsonNode item : f.list("alerts")) {
            List<Object> l = append(loc, "alerts", alerts.size());
            try {
                alerts.add(alert(item, l));
            } catch (ContractViolation v) {
                f.errors.addAll(v.errors());
                alerts.add(null);
            }
        }
        f.throwIfInvalid();
        return new SyncBatch(nodeId, batchId, List.copyOf(events), List.copyOf(alerts));
    }

    static Map<String, Object> error(String type, List<Object> loc, String msg, Object input) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", type);
        e.put("loc", loc);
        e.put("msg", msg);
        e.put("input", input);
        return e;
    }

    private static List<Object> append(List<Object> loc, Object... more) {
        List<Object> l = new ArrayList<>(loc);
        l.addAll(List.of(more));
        return List.copyOf(l);
    }

    /** Reads typed fields from a JSON object and accumulates validation errors. */
    private static final class Fields {
        private final JsonNode node;
        private final List<Object> loc;
        private final List<Map<String, Object>> errors = new ArrayList<>();

        Fields(JsonNode node, List<Object> loc) {
            this.node = node;
            this.loc = loc;
            if (node == null || !node.isObject()) {
                errors.add(error("model_attributes_type", loc,
                        "Input should be a valid dictionary or object to extract fields from", node));
            }
        }

        boolean isObject() {
            return node != null && node.isObject();
        }

        void throwIfInvalid() {
            if (!errors.isEmpty()) {
                throw new ContractViolation(errors);
            }
        }

        private JsonNode get(String name) {
            JsonNode v = node.get(name);
            if (v == null) {
                errors.add(error("missing", append(loc, name), "Field required", node));
            }
            return v;
        }

        String str(String name, boolean uuidDefault) {
            if (uuidDefault && !node.has(name)) {
                return UUID.randomUUID().toString();
            }
            JsonNode v = get(name);
            if (v == null) {
                return null;
            }
            if (!v.isTextual()) {
                errors.add(error("string_type", append(loc, name), "Input should be a valid string", v));
                return null;
            }
            return v.textValue();
        }

        String literal(String name, List<String> allowed) {
            JsonNode v = get(name);
            if (v == null) {
                return null;
            }
            if (!v.isTextual() || !allowed.contains(v.textValue())) {
                errors.add(error("literal_error", append(loc, name),
                        "Input should be " + describe(allowed), v));
                return null;
            }
            return v.textValue();
        }

        double number(String name) {
            JsonNode v = get(name);
            if (v == null) {
                return 0;
            }
            if (v.isNumber()) {
                return v.doubleValue();
            }
            if (v.isTextual()) {
                try {
                    return Double.parseDouble(v.textValue().strip());
                } catch (NumberFormatException e) {
                    errors.add(error("float_parsing", append(loc, name),
                            "Input should be a valid number, unable to parse string as a number", v));
                    return 0;
                }
            }
            errors.add(error("float_type", append(loc, name), "Input should be a valid number", v));
            return 0;
        }

        long integer(String name, Long min) {
            JsonNode v = get(name);
            if (v == null) {
                return 0;
            }
            long value;
            if (v.isIntegralNumber() && v.canConvertToLong()) {
                value = v.longValue();
            } else if (v.isNumber()) {
                double d = v.doubleValue();
                if (d != Math.rint(d) || Double.isInfinite(d)) {
                    errors.add(error("int_from_float", append(loc, name),
                            "Input should be a valid integer, got a number with a fractional part", v));
                    return 0;
                }
                value = (long) d;
            } else if (v.isTextual()) {
                try {
                    value = Long.parseLong(v.textValue().strip());
                } catch (NumberFormatException e) {
                    errors.add(error("int_parsing", append(loc, name),
                            "Input should be a valid integer, unable to parse string as an integer", v));
                    return 0;
                }
            } else {
                errors.add(error("int_type", append(loc, name), "Input should be a valid integer", v));
                return 0;
            }
            if (min != null && value < min) {
                errors.add(error("greater_than_equal", append(loc, name),
                        "Input should be greater than or equal to " + min, v));
            }
            return value;
        }

        List<JsonNode> list(String name) {
            JsonNode v = node.get(name);
            if (v == null) {
                return List.of();              // default []
            }
            if (!v.isArray()) {
                errors.add(error("list_type", append(loc, name), "Input should be a valid list", v));
                return List.of();
            }
            List<JsonNode> items = new ArrayList<>();
            v.forEach(items::add);
            return items;
        }

        private static String describe(List<String> allowed) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < allowed.size(); i++) {
                if (i > 0) {
                    sb.append(i == allowed.size() - 1 ? " or " : ", ");
                }
                sb.append('\'').append(allowed.get(i)).append('\'');
            }
            return sb.toString();
        }
    }
}
