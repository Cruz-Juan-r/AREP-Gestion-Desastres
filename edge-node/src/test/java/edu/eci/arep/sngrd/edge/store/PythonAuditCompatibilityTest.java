package edu.eci.arep.sngrd.edge.store;

import com.fasterxml.jackson.databind.JsonNode;
import edu.eci.arep.sngrd.shared.json.Json;
import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Cross-language check: an audit chain written by the Java edge must be accepted by
 * {@code common/auditlog.verify()} in Python (the function the experiment uses), and the
 * experiment's tampering must be detected by it. Skipped when no Python 3 interpreter is
 * found (e.g. inside the Docker build); set PYTHON to choose one explicitly.
 */
class PythonAuditCompatibilityTest {

    private static final String SCRIPT = """
            import json, sqlite3, sys
            sys.path.insert(0, sys.argv[1])
            from common.auditlog import verify
            c = sqlite3.connect(sys.argv[2])
            before = verify(c)
            target = max(1, before["entries"] // 2)
            c.execute("UPDATE audit SET detail = replace(detail, '\\"critical\\"', '\\"warning\\"') || ' ' "
                      "WHERE seq = ?", (target,))
            after = verify(c)
            c.rollback()
            print(json.dumps({"before": before, "target": target, "after": after}))
            """;

    @TempDir
    Path dir;

    @Test
    void pythonVerifierAcceptsJavaChainAndDetectsTampering() throws Exception {
        Path root = projectRoot();
        String python = findPython();
        assumeTrue(root != null, "common/auditlog.py not found");
        assumeTrue(python != null, "no Python 3 interpreter available");

        String javaHead;
        try (SqliteFixture db = new SqliteFixture(dir)) {
            EdgeStore store = db.store;
            store.auditLinkChange(true, Map.of("batch_id", "b-0"));
            for (int i = 0; i < 12; i++) {
                double value = i % 3 == 0 ? 4.62 + i : 0.0001 * i;          // mixes float formats
                Telemetry t = new Telemetry("e-" + i, "Mocoa-ñ-" + i, "river_level", value, "m",
                        1.15, -76.65, i, 1_700_000_000_000L + i);
                Alert a = new Alert("a-" + i, t.eventId(), t.deviceId(), t.sensorType(),
                        i % 2 == 0 ? "critical" : "warning", i % 4 == 0 ? "zscore" : "threshold",
                        value, 1.5, t.lat(), t.lon(), t.tsMs(), t.tsMs() + 3, "edge");
                store.save(t, a);
            }
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("batch_id", "b-1");
            ack.put("events_accepted", 10);
            ack.put("events_duplicated", 2);
            ack.put("alerts_accepted", 12);
            ack.put("alerts_duplicated", 0);
            Pending p = store.pending(500, 200);
            store.markSynced(p.eventIds(), p.alertIds(), "b-1", ack);
            store.auditLinkChange(false, Map.of("error", "HttpConnectTimeoutException: \"timed out\"\n"));
            Map<String, Object> v = store.verifyAudit();
            assertTrue((Boolean) v.get("valid"));
            javaHead = (String) v.get("head");
        }

        JsonNode out = runPython(python, root, dir.resolve("edge.db"));
        assertTrue(out.at("/before/valid").asBoolean(), "Python rejected the chain written by Java: " + out);
        assertEquals(15, out.at("/before/entries").asInt());
        assertEquals(javaHead, out.at("/before/head").asText());
        assertFalse(out.at("/after/valid").asBoolean());
        assertEquals(out.at("/target").asInt(), out.at("/after/first_invalid_seq").asInt());
    }

    private static JsonNode runPython(String python, Path root, Path db) throws IOException, InterruptedException {
        Path script = db.resolveSibling("verify_from_python.py");
        Files.writeString(script, SCRIPT, StandardCharsets.UTF_8);
        List<String> cmd = List.of(python, "-I", script.toString(), root.toString(), db.toString());
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String output = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(proc.waitFor(60, TimeUnit.SECONDS), "python timed out");
        assertEquals(0, proc.exitValue(), "python failed: " + output);
        String lastLine = output.substring(output.lastIndexOf('\n') + 1);
        return Json.MAPPER.readTree(lastLine);
    }

    /** Repository root (the directory that contains common/auditlog.py). */
    private static Path projectRoot() {
        Path p = Path.of("").toAbsolutePath();
        for (int i = 0; i < 3 && p != null; i++, p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("common").resolve("auditlog.py"))) {
                return p;
            }
        }
        return null;
    }

    private static String findPython() {
        List<String> candidates = new ArrayList<>();
        String env = System.getenv("PYTHON");
        if (env != null && !env.isBlank()) {
            candidates.add(env);
        }
        candidates.addAll(List.of("python", "python3", "py"));
        for (String c : candidates) {
            try {
                Process proc = new ProcessBuilder(c, "--version").redirectErrorStream(true).start();
                String v = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (proc.waitFor(10, TimeUnit.SECONDS) && proc.exitValue() == 0 && v.startsWith("Python 3")) {
                    return c;
                }
            } catch (IOException | InterruptedException e) {
                // try the next candidate
            }
        }
        return null;
    }
}
