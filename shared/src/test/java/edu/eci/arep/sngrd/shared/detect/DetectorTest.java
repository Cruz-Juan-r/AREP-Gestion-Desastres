package edu.eci.arep.sngrd.shared.detect;

import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.Telemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DetectorTest {

    private static final long T0 = 1_700_000_000_000L;
    private Detector detector;

    @BeforeEach
    void setUp() {
        detector = new Detector("edge", 60, 20, 5.0, 10_000, () -> T0 + 999);
    }

    private static Telemetry reading(String device, String type, double value, long tsMs) {
        return new Telemetry(UUID.randomUUID().toString(), device, type, value, "u", 0.5, -76.6, 0, tsMs);
    }

    /** Feeds a stable baseline (alternating around {@code base}) of n readings, one per second. */
    private long baseline(String device, String type, double base, double noise, int n) {
        long ts = T0;
        for (int i = 0; i < n; i++) {
            assertNull(detector.evaluate(reading(device, type, base + (i % 2 == 0 ? noise : -noise), ts)));
            ts += 1000;
        }
        return ts;
    }

    @Test
    void criticalThresholdUsesCriticalLimitRatio() {
        Telemetry t = reading("rv-01", "river_level", 4.6, T0);
        Alert a = detector.evaluate(t);
        assertNotNull(a);
        assertEquals("critical", a.severity());
        assertEquals("threshold", a.rule());
        assertEquals(1.022, a.score());                 // round(4.6 / 4.5, 3)
        assertEquals(t.eventId(), a.eventId());
        assertEquals(T0, a.tsEventMs());
        assertEquals(T0 + 999, a.tsAlertMs());
        assertEquals("edge", a.source());
    }

    @Test
    void warningThresholdUsesWarningLimitRatio() {
        Alert a = detector.evaluate(reading("rf-01", "rainfall", 35.0, T0));
        assertNotNull(a);
        assertEquals("warning", a.severity());
        assertEquals("threshold", a.rule());
        assertEquals(1.167, a.score());                 // round(35 / 30, 3)
    }

    @Test
    void thresholdsMatchThePythonReference() {
        assertNotNull(detector.evaluate(reading("sz-01", "seismic", 0.10, T0)));
        assertEquals("critical", new Detector("edge").evaluate(reading("sz-02", "seismic", 0.10, T0)).severity());
        assertEquals("warning", new Detector("edge").evaluate(reading("sz-03", "seismic", 0.05, T0)).severity());
        assertNull(new Detector("edge").evaluate(reading("sz-04", "seismic", 0.049, T0)));
        assertNull(new Detector("edge").evaluate(reading("rv-02", "river_level", 3.49, T0)));
    }

    @Test
    void droneWithOnePersonIsCriticalAndNeverUsesZscore() {
        Alert a = detector.evaluate(reading("dr-01", "drone", 1.0, T0));
        assertEquals("critical", a.severity());
        assertEquals(1.0, a.score());

        // zero persons for a long time and then still zero: no statistical rule for drones
        Detector d = new Detector("edge");
        for (int i = 0; i < 40; i++) {
            assertNull(d.evaluate(reading("dr-02", "drone", 0.0, T0 + i * 1000L)));
        }
    }

    @Test
    void zscoreDetectsSuddenRiseBelowThreshold() {
        long ts = baseline("rv-03", "river_level", 2.0, 0.01, 30);
        Alert a = detector.evaluate(reading("rv-03", "river_level", 2.6, ts));   // below 3.5 m
        assertNotNull(a);
        assertEquals("warning", a.severity());
        assertEquals("zscore", a.rule());
        assertEquals(Detector.round3(Math.abs(2.6 - 2.0) / expectedStd(30)), a.score(), 1e-9);
    }

    private static double expectedStd(int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 2.0 + (i % 2 == 0 ? 0.01 : -0.01);
        }
        double mean = sum / n;
        double sq = 0;
        for (int i = 0; i < n; i++) {
            double x = 2.0 + (i % 2 == 0 ? 0.01 : -0.01);
            sq += (x - mean) * (x - mean);
        }
        return Math.sqrt(sq / (n - 1));
    }

    @Test
    void zscoreNeedsMinimumSamples() {
        long ts = baseline("rv-04", "river_level", 2.0, 0.01, 19);
        assertNull(detector.evaluate(reading("rv-04", "river_level", 2.6, ts)));
        assertEquals(20, detector.windowSize("rv-04"));   // 2.6 was not anomalous yet, so it joined
    }

    @Test
    void cooldownSuppressesRepeatedAlertsOfTheSameEpisode() {
        assertNotNull(detector.evaluate(reading("rv-05", "river_level", 4.8, T0)));
        assertNull(detector.evaluate(reading("rv-05", "river_level", 4.9, T0 + 1_000)));
        assertNull(detector.evaluate(reading("rv-05", "river_level", 4.9, T0 + 9_999)));
        assertNotNull(detector.evaluate(reading("rv-05", "river_level", 4.9, T0 + 10_000)));
        // cooldown is per device
        assertNotNull(detector.evaluate(reading("rv-06", "river_level", 4.9, T0 + 1_000)));
    }

    @Test
    void anomalousReadingsDoNotEnterTheBaselineWindow() {
        long ts = baseline("rf-02", "rainfall", 5.0, 0.2, 25);
        assertEquals(25, detector.windowSize("rf-02"));
        detector.evaluate(reading("rf-02", "rainfall", 60.0, ts));          // threshold
        detector.evaluate(reading("rf-02", "rainfall", 9.0, ts + 1000));    // z-score
        assertEquals(25, detector.windowSize("rf-02"));
    }

    @Test
    void windowIsBoundedAndResetClearsState() {
        baseline("rf-03", "rainfall", 5.0, 0.2, 100);
        assertEquals(60, detector.windowSize("rf-03"));
        detector.reset();
        assertEquals(0, detector.windowSize("rf-03"));
    }

    @Test
    void scoreRoundingMatchesPythonRound() {
        assertEquals(1.022, Detector.round3(1.0225));
        assertEquals(1.028, Detector.round3(1.0275));
        assertEquals(2.001, Detector.round3(2.0005));
        assertEquals(1.022, Detector.round3(4.6 / 4.5));
    }
}
