package edu.eci.arep.sngrd.cloud;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The cloud receiver is idempotent: resends (e.g. after a lost ACK) never create duplicates (H3). */
@SpringBootTest
@AutoConfigureMockMvc
class SyncIdempotencyTest {

    // Not @TempDir: on Windows the cached Spring context keeps the SQLite file open.
    static final Path dir = Path.of("target", "test-data", "cloud-api-" + UUID.randomUUID());

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("cloud.db", () -> dir.resolve("cloud.db").toString());
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void reset() throws Exception {
        mvc.perform(post("/admin/reset")).andExpect(status().isOk());
        mvc.perform(post("/admin/fault").param("ack_loss", "0")).andExpect(content().json("{\"ack_loss\":0.0}"));
    }

    private static String event(String id) {
        return "{\"event_id\":\"" + id + "\",\"device_id\":\"rv-01\",\"sensor_type\":\"river_level\",\"value\":2.3,"
                + "\"unit\":\"m\",\"lat\":1.15,\"lon\":-76.65,\"seq\":1,\"ts_ms\":1700000000000}";
    }

    private static String alert(String id, String eventId) {
        return "{\"alert_id\":\"" + id + "\",\"event_id\":\"" + eventId + "\",\"device_id\":\"rv-01\","
                + "\"sensor_type\":\"river_level\",\"severity\":\"critical\",\"rule\":\"threshold\",\"value\":4.6,"
                + "\"score\":1.022,\"lat\":1.15,\"lon\":-76.65,\"ts_event_ms\":1700000000000,"
                + "\"ts_alert_ms\":1700000000002,\"source\":\"edge\"}";
    }

    private static String batch(String batchId, List<String> eventIds, List<String> alerts) {
        return "{\"node_id\":\"pmu-test\",\"batch_id\":\"" + batchId + "\",\"events\":["
                + eventIds.stream().map(SyncIdempotencyTest::event).collect(Collectors.joining(","))
                + "],\"alerts\":[" + String.join(",", alerts) + "]}";
    }

    private ResultActions sync(String body) throws Exception {
        return mvc.perform(post("/api/v1/sync").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void resendingTheSameBatchStoresNothingTwice() throws Exception {
        String body = batch("b-1", List.of("e-1", "e-2", "e-3"), List.of(alert("a-1", "e-1")));
        sync(body).andExpect(status().isOk()).andExpect(content().json(
                "{\"batch_id\":\"b-1\",\"events_accepted\":3,\"events_duplicated\":0,"
                        + "\"alerts_accepted\":1,\"alerts_duplicated\":0}", true));
        sync(body).andExpect(status().isOk()).andExpect(content().json(
                "{\"batch_id\":\"b-1\",\"events_accepted\":0,\"events_duplicated\":3,"
                        + "\"alerts_accepted\":0,\"alerts_duplicated\":1}", true));

        mvc.perform(get("/api/v1/stats"))
                .andExpect(jsonPath("$.events_stored").value(3))
                .andExpect(jsonPath("$.events_distinct").value(3))
                .andExpect(jsonPath("$.alerts_stored").value(1))
                .andExpect(jsonPath("$.by_origin.pmu-test").value(3))
                .andExpect(jsonPath("$.sync_batches").value(2))
                .andExpect(jsonPath("$.events_duplicated").value(3))
                .andExpect(jsonPath("$.alerts_duplicated").value(1));
    }

    @Test
    void lostAckCommitsTheBatchAndTheResendIsRecognisedAsDuplicate() throws Exception {
        String body = batch("b-2", List.of("e-10", "e-11"), List.of());
        mvc.perform(post("/admin/fault").param("ack_loss", "1"));
        sync(body).andExpect(status().isServiceUnavailable())
                .andExpect(content().json("{\"detail\":\"simulated lost acknowledgement\"}"));
        mvc.perform(get("/api/v1/stats"))
                .andExpect(jsonPath("$.events_stored").value(2))
                .andExpect(jsonPath("$.acks_dropped").value(1));

        mvc.perform(post("/admin/fault").param("ack_loss", "0"));
        sync(body).andExpect(status().isOk())
                .andExpect(jsonPath("$.events_accepted").value(0))
                .andExpect(jsonPath("$.events_duplicated").value(2));
        mvc.perform(get("/api/v1/stats"))
                .andExpect(jsonPath("$.events_stored").value(2))
                .andExpect(jsonPath("$.events_distinct").value(2));
    }

    @Test
    void overlappingBatchesOnlyAddNewEvents() throws Exception {
        sync(batch("b-3", List.of("e-20", "e-21"), List.of())).andExpect(jsonPath("$.events_accepted").value(2));
        sync(batch("b-4", List.of("e-21", "e-22"), List.of()))
                .andExpect(jsonPath("$.events_accepted").value(1))
                .andExpect(jsonPath("$.events_duplicated").value(1));
        mvc.perform(get("/api/v1/events/ids"))
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$", containsInAnyOrder("e-20", "e-21", "e-22")));
    }

    @Test
    void missingIdsAndListsTakeTheContractDefaults() throws Exception {
        sync("{\"node_id\":\"pmu-test\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.events_accepted").value(0))
                .andExpect(jsonPath("$.batch_id").isString());
    }

    @Test
    void baselineIngestIsIdempotentToo() throws Exception {
        String id = UUID.randomUUID().toString();
        String critical = event(id).replace("\"value\":2.3", "\"value\":4.8");
        mvc.perform(post("/api/v1/ingest").contentType(MediaType.APPLICATION_JSON).content(critical))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value(id))
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.alert.severity").value("critical"))
                .andExpect(jsonPath("$.alert.source").value("cloud"));
        mvc.perform(post("/api/v1/ingest").contentType(MediaType.APPLICATION_JSON).content(critical))
                .andExpect(jsonPath("$.duplicated").value(true))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"alert\":null")));
        mvc.perform(get("/api/v1/stats"))
                .andExpect(jsonPath("$.events_stored").value(1))
                .andExpect(jsonPath("$.alerts_stored").value(1))
                .andExpect(jsonPath("$.ingest_requests").value(2))
                .andExpect(jsonPath("$.by_origin.direct").value(1));
    }

    @Test
    void invalidInputGetsFastApiStyleErrors() throws Exception {
        sync("{\"node_id\":\"n\",\"events\":[" + event("x").replace("river_level", "volcano") + "]}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail[0].type").value("literal_error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "\"loc\":[\"body\",\"events\",0,\"sensor_type\"]")));
        sync("{not json").andExpect(status().isUnprocessableEntity());
        sync("{\"events\":[]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail[0].type").value("missing"));
        mvc.perform(post("/admin/fault").param("ack_loss", "1.5"))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"detail\":\"ack_loss must be in [0, 1]\"}"));
        mvc.perform(post("/admin/fault")).andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/api/v1/stats")).andExpect(jsonPath("$.events_stored").value(0));
    }

    @Test
    void healthAndDocs() throws Exception {
        mvc.perform(get("/health")).andExpect(content().json("{\"status\":\"ok\"}", true));
        mvc.perform(get("/openapi.json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Nube nacional - prototipo"));
    }
}
