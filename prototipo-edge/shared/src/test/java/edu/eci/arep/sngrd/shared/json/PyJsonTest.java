package edu.eci.arep.sngrd.shared.json;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expected strings were produced by CPython 3.13 {@code json.dumps}. */
class PyJsonTest {

    @Test
    void sortsKeysAndEscapesNonAscii() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("value", 4.62);
        detail.put("severity", "critical");
        detail.put("event_id", "e-1");
        detail.put("device_id", "Mocoa-ñ-01");
        detail.put("rule", "threshold");
        assertEquals("{\"device_id\":\"Mocoa-\\u00f1-01\",\"event_id\":\"e-1\",\"rule\":\"threshold\","
                + "\"severity\":\"critical\",\"value\":4.62}", PyJson.dumpsSorted(detail));
    }

    @Test
    void sortsNestedMaps() {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("batch_id", "b-1");
        ack.put("events_accepted", 498);
        ack.put("events_duplicated", 2);
        ack.put("alerts_accepted", 0);
        ack.put("alerts_duplicated", 0);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("events", 500);
        detail.put("alerts", 0);
        detail.put("ack", ack);
        assertEquals("{\"ack\":{\"alerts_accepted\":0,\"alerts_duplicated\":0,\"batch_id\":\"b-1\","
                + "\"events_accepted\":498,\"events_duplicated\":2},\"alerts\":0,\"events\":500}",
                PyJson.dumpsSorted(detail));
    }

    @Test
    void floatReprMatchesPython() {
        assertEquals("5.0", PyJson.floatRepr(5.0));
        assertEquals("4.62", PyJson.floatRepr(4.62));
        assertEquals("0.0001", PyJson.floatRepr(0.0001));
        assertEquals("1e-05", PyJson.floatRepr(1e-05));
        assertEquals("1e+16", PyJson.floatRepr(1e16));
        assertEquals("123456789012345.6", PyJson.floatRepr(123456789012345.6));
        assertEquals("1.5e+300", PyJson.floatRepr(1.5e300));
        assertEquals("-0.0", PyJson.floatRepr(-0.0));
        assertEquals("0.30000000000000004", PyJson.floatRepr(0.1 + 0.2));
        assertEquals("2.5e-07", PyJson.floatRepr(2.5e-07));
        assertEquals("1000000000000000.0", PyJson.floatRepr(1e15));
    }

    @Test
    void escapesLikeEnsureAscii() {
        assertEquals("\"a\\\"b\\\\c\\n\\u007f\\u0001\\u00e9\\ud83d\\ude00\"",
                PyJson.dumps("a\"b\\c\n\u007f\u0001é😀"));
    }

    @Test
    void compactListWithoutSpaces() {
        assertEquals("[\"x\",1700000000000,true,null]",
                PyJson.dumps(java.util.Arrays.asList("x", 1_700_000_000_000L, true, null)));
    }
}
