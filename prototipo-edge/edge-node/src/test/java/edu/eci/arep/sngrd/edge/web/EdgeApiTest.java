package edu.eci.arep.sngrd.edge.web;

import edu.eci.arep.sngrd.edge.node.EdgeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the edge node (same JSON as the Python version). No broker or cloud needed. */
@SpringBootTest
@AutoConfigureMockMvc
class EdgeApiTest {

    // Not @TempDir: on Windows the cached Spring context keeps the SQLite file open.
    static final Path dir = Path.of("target", "test-data", "edge-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("edge.db", () -> dir.resolve("edge.db").toString());
        r.add("edge.mqtt-port", () -> 1);                         // nothing listens there
        r.add("edge.cloud-url", () -> "http://127.0.0.1:1");
        r.add("edge.autostart", () -> false);                      // no MQTT client, no sync agent
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    EdgeNode node;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/admin/reset")).andExpect(status().isOk()).andExpect(content().json("{\"reset\":true}"));
    }

    private static byte[] reading(String id, String type, double value) {
        return ("{\"event_id\":\"" + id + "\",\"device_id\":\"dev-" + id + "\",\"sensor_type\":\"" + type + "\",\"value\":"
                + value + ",\"unit\":\"m\",\"lat\":1.15,\"lon\":-76.65,\"seq\":1,\"ts_ms\":1700000000000}")
                .getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void ingestCountsStoredDuplicateAndInvalidMessages() throws Exception {
        String id = UUID.randomUUID().toString();
        assertEquals(EdgeNode.Ingested.Status.STORED, node.ingest(reading(id, "river_level", 4.8)).status());
        assertEquals(EdgeNode.Ingested.Status.DUPLICATE, node.ingest(reading(id, "river_level", 4.8)).status());
        assertEquals(EdgeNode.Ingested.Status.INVALID, node.ingest("{\"bad\":1}".getBytes(StandardCharsets.UTF_8)).status());

        mvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node_id").value("pmu-municipio-01"))
                .andExpect(jsonPath("$.readings_processed").value(1))
                .andExpect(jsonPath("$.duplicates_ignored").value(1))
                .andExpect(jsonPath("$.invalid_messages").value(1))
                .andExpect(jsonPath("$.store.events_total").value(1))
                .andExpect(jsonPath("$.store.events_pending").value(1))
                .andExpect(jsonPath("$.store.alerts_total").value(1))
                .andExpect(jsonPath("$.store.audit_entries").value(1))
                .andExpect(jsonPath("$.sync.batches_ok").value(0))
                .andExpect(content().string(containsString("\"processing_ms_p95\":null")));

        mvc.perform(get("/alerts/recent").param("limit", "5"))
                .andExpect(jsonPath("$[0].event_id").value(id))
                .andExpect(jsonPath("$[0].severity").value("critical"))
                .andExpect(jsonPath("$[0].source").value("edge"))
                .andExpect(jsonPath("$[0].synced").value(false));

        mvc.perform(get("/audit/verify"))
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.entries").value(1))
                .andExpect(jsonPath("$.first_invalid_seq").isEmpty());
    }

    @Test
    void metricsKeepThePythonKeyOrder() throws Exception {
        String body = mvc.perform(get("/metrics")).andReturn().getResponse().getContentAsString();
        String[] keys = {"node_id", "uptime_s", "mqtt_connected", "readings_processed", "duplicates_ignored",
                "invalid_messages", "processing_ms_p50", "processing_ms_p95", "processing_ms_p99", "store", "sync"};
        int last = -1;
        for (String k : keys) {
            int at = body.indexOf("\"" + k + "\"");
            assertEquals(true, at > last, "key out of order: " + k);
            last = at;
        }
    }

    @Test
    void healthReportsLinkState() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.node_id").value("pmu-municipio-01"))
                .andExpect(jsonPath("$.mqtt_connected").value(false));
    }

    @Test
    void servesTheDashboard() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("COP local")));
    }

    @Test
    void recentAlertsAreNewestFirst() throws Exception {
        node.ingest(reading("e-a", "rainfall", 60));
        Thread.sleep(5);
        node.ingest(reading("e-b", "seismic", 0.2));
        mvc.perform(get("/alerts/recent"))
                .andExpect(jsonPath("$[*].event_id", contains("e-b", "e-a")));
    }
}
