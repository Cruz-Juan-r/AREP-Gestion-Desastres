package edu.eci.arep.sngrd.edge.store;

import edu.eci.arep.sngrd.shared.json.PyJson;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only, hash-chained audit log stored in SQLite.
 *
 * <p>Each entry stores the SHA-256 of (previous hash + entry content). Changing,
 * deleting or reordering any past entry breaks every hash after it, so
 * {@link #verify()} pinpoints the first tampered entry.
 *
 * <p>Table, detail encoding and digest are byte-compatible with
 * {@code common/auditlog.py}, so the Python {@code verify()} used by the experiment
 * validates chains written by this class:
 * <pre>
 *   detail = json.dumps(detail, sort_keys=True, separators=(",", ":"))
 *   hash   = sha256(json.dumps([prev_hash, ts_ms, kind, ref_id, detail], separators=(",", ":")))
 * </pre>
 */
public class AuditLog {

    public static final String GENESIS = "0".repeat(64);

    public static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS audit (
                seq       INTEGER PRIMARY KEY AUTOINCREMENT,
                ts_ms     INTEGER NOT NULL,
                kind      TEXT    NOT NULL,
                ref_id    TEXT    NOT NULL,
                detail    TEXT    NOT NULL,
                prev_hash TEXT    NOT NULL,
                hash      TEXT    NOT NULL
            )""";

    private final JdbcTemplate jdbc;

    public AuditLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute(SCHEMA);
    }

    /** Appends an entry. Must run inside the caller's transaction (same connection). */
    public void append(long tsMs, String kind, String refId, Map<String, ?> detail) {
        String d = PyJson.dumpsSorted(detail);
        String prev = head();
        String h = digest(prev, tsMs, kind, refId, d);
        jdbc.update("INSERT INTO audit (ts_ms, kind, ref_id, detail, prev_hash, hash) VALUES (?,?,?,?,?,?)",
                tsMs, kind, refId, d, prev, h);
    }

    /** Hash of the last entry, or the genesis hash for an empty chain. */
    public String head() {
        List<String> last = jdbc.queryForList("SELECT hash FROM audit ORDER BY seq DESC LIMIT 1", String.class);
        return last.isEmpty() ? GENESIS : last.get(0);
    }

    public static String digest(String prevHash, long tsMs, String kind, String refId, String detail) {
        String material = PyJson.dumps(Arrays.asList(prevHash, tsMs, kind, refId, detail));
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same result shape as the Python {@code verify()}. */
    public Map<String, Object> verify() {
        return jdbc.query("SELECT seq, ts_ms, kind, ref_id, detail, prev_hash, hash FROM audit ORDER BY seq", rs -> {
            String prev = GENESIS;
            int n = 0;
            while (rs.next()) {
                n++;
                long seq = rs.getLong(1);
                String expected = digest(prev, rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5));
                if (!prev.equals(rs.getString(6)) || !expected.equals(rs.getString(7))) {
                    Map<String, Object> bad = new LinkedHashMap<>();
                    bad.put("valid", false);
                    bad.put("entries", n);
                    bad.put("first_invalid_seq", seq);
                    return bad;
                }
                prev = rs.getString(7);
            }
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("valid", true);
            ok.put("entries", n);
            ok.put("first_invalid_seq", null);
            ok.put("head", prev);
            return ok;
        });
    }
}
