package edu.eci.arep.sngrd.edge.node;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;

/** Bounded ring buffer of per-message processing times (Python: deque(maxlen=20000)). */
public class ProcessingStats {

    private final double[] samples;
    private int next;
    private int size;

    public ProcessingStats(int capacity) {
        this.samples = new double[capacity];
    }

    public synchronized void add(double ms) {
        samples[next] = ms;
        next = (next + 1) % samples.length;
        size = Math.min(size + 1, samples.length);
    }

    public synchronized void clear() {
        next = 0;
        size = 0;
    }

    /** Sorted copy of the current samples. */
    public synchronized double[] sorted() {
        double[] copy = Arrays.copyOf(samples, size);    // order is irrelevant: it is sorted next
        Arrays.sort(copy);
        return copy;
    }

    /** Same estimator as the Python edge: p[min(n - 1, int(q * n))], rounded to 3 decimals. */
    public static Double percentile(double[] sorted, double q) {
        if (sorted.length == 0) {
            return null;
        }
        double v = sorted[Math.min(sorted.length - 1, (int) (q * sorted.length))];
        return round(v, 3);
    }

    /** Python's round(x, n). */
    public static double round(double x, int digits) {
        return new BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).doubleValue();
    }
}
