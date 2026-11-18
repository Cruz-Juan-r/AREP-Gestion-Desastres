package edu.eci.arep.sngrd.shared.detect;

import edu.eci.arep.sngrd.shared.model.Alert;
import edu.eci.arep.sngrd.shared.model.Telemetry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Lightweight event detector used both at the edge and in the cloud baseline.
 *
 * <p>Two complementary rules run on every reading:
 * <ul>
 *   <li>Threshold rule: fixed warning/critical limits per sensor type (domain rules,
 *       e.g. a river gauge above its overflow level).</li>
 *   <li>Rolling z-score rule: per-device statistical anomaly detection over a sliding
 *       window. It catches abrupt changes that are still below the fixed limits
 *       (e.g. a sudden rise of a river that has not overflowed yet).</li>
 * </ul>
 * A per-device cooldown avoids alert storms: one physical episode should produce one
 * alert, not one per reading. Parameters and arithmetic are identical to the Python
 * reference implementation of the previous delivery.
 */
public class Detector {

    /** (warning, critical) limits per sensor type. */
    public static final Map<String, double[]> THRESHOLDS = Map.of(
            "river_level", new double[]{3.5, 4.5},   // metres above gauge zero
            "rainfall", new double[]{30.0, 50.0},    // mm/h
            "seismic", new double[]{0.05, 0.10},     // peak ground acceleration, g
            "drone", new double[]{1.0, 1.0});        // persons detected by the onboard classifier

    public static final int DEFAULT_WINDOW = 60;
    public static final int DEFAULT_MIN_SAMPLES = 20;
    public static final double DEFAULT_Z_LIMIT = 5.0;
    public static final long DEFAULT_COOLDOWN_MS = 10_000;

    private static final long NEVER = -1_000_000_000_000L;   // Python: -10**12

    private final String source;
    private final int window;
    private final int minSamples;
    private final double zLimit;
    private final long cooldownMs;
    private final LongSupplier clock;
    private final Map<String, DeviceState> state = new HashMap<>();

    public Detector(String source) {
        this(source, DEFAULT_WINDOW, DEFAULT_MIN_SAMPLES, DEFAULT_Z_LIMIT, DEFAULT_COOLDOWN_MS,
                System::currentTimeMillis);
    }

    public Detector(String source, int window, int minSamples, double zLimit, long cooldownMs,
                    LongSupplier clock) {
        this.source = source;
        this.window = window;
        this.minSamples = minSamples;
        this.zLimit = zLimit;
        this.cooldownMs = cooldownMs;
        this.clock = clock;
    }

    public synchronized void reset() {
        state.clear();
    }

    /** Returns the alert raised by this reading, or {@code null}. */
    public synchronized Alert evaluate(Telemetry t) {
        DeviceState st = state.computeIfAbsent(t.deviceId(), k -> new DeviceState());
        String severity = null;
        String rule = null;
        double score = 0.0;

        double[] limits = THRESHOLDS.get(t.sensorType());
        double warn = limits[0];
        double crit = limits[1];
        if (t.value() >= crit) {
            severity = "critical";
            rule = "threshold";
            score = t.value() / crit;
        } else if (t.value() >= warn) {
            severity = "warning";
            rule = "threshold";
            score = t.value() / warn;
        } else if (!"drone".equals(t.sensorType()) && st.window.size() >= minSamples) {
            int n = st.window.size();
            double sum = 0.0;
            for (double x : st.window) {
                sum += x;
            }
            double mean = sum / n;
            double sq = 0.0;
            for (double x : st.window) {
                sq += (x - mean) * (x - mean);
            }
            double std = Math.sqrt(sq / (n - 1));
            double z = std > 1e-9 ? Math.abs(t.value() - mean) / std : 0.0;
            if (z >= zLimit) {
                severity = "warning";
                rule = "zscore";
                score = z;
            }
        }

        // Anomalous readings are not added to the baseline window so that a
        // sustained anomaly does not become the new normal immediately.
        if (severity == null) {
            st.window.addLast(t.value());
            if (st.window.size() > window) {
                st.window.removeFirst();
            }
            return null;
        }
        if (t.tsMs() - st.lastAlertMs < cooldownMs) {
            return null;
        }
        st.lastAlertMs = t.tsMs();
        return new Alert(UUID.randomUUID().toString(), t.eventId(), t.deviceId(), t.sensorType(),
                severity, rule, t.value(), round3(score), t.lat(), t.lon(), t.tsMs(),
                clock.getAsLong(), source);
    }

    /** Number of baseline samples kept for a device (visible for tests). */
    synchronized int windowSize(String deviceId) {
        DeviceState st = state.get(deviceId);
        return st == null ? 0 : st.window.size();
    }

    /** Python's round(x, 3): correctly rounded, ties to even, on the exact binary value. */
    static double round3(double x) {
        return new BigDecimal(x).setScale(3, RoundingMode.HALF_EVEN).doubleValue();
    }

    private static final class DeviceState {
        final ArrayDeque<Double> window = new ArrayDeque<>();
        long lastAlertMs = NEVER;
    }
}
