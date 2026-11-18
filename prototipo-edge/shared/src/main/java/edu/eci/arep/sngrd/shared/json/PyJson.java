package edu.eci.arep.sngrd.shared.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Byte-exact equivalent of Python's {@code json.dumps(obj, separators=(",", ":"))}
 * with the default {@code ensure_ascii=True}; maps can be emitted with sorted keys
 * ({@code sort_keys=True}).
 *
 * <p>The audit chain hashes JSON text, so the Java edge node must produce exactly the
 * same bytes as the Python reference ({@code common/auditlog.py}) for the experiment's
 * Python verifier to accept the chain.
 */
public final class PyJson {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PyJson() {
    }

    /** {@code json.dumps(value, separators=(",", ":"))} (insertion order for maps). */
    public static String dumps(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, false);
        return sb.toString();
    }

    /** {@code json.dumps(value, sort_keys=True, separators=(",", ":"))}. */
    public static String dumpsSorted(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean sortKeys) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence s) {
            string(sb, s.toString());
        } else if (v instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(floatRepr(((Number) v).doubleValue()));
        } else if (v instanceof BigDecimal bd) {
            sb.append(floatRepr(bd.doubleValue()));
        } else if (v instanceof Number n) {
            sb.append(n.toString());                     // int, long, BigInteger
        } else if (v instanceof Map<?, ?> m) {
            List<Map.Entry<?, ?>> entries = new ArrayList<>(m.entrySet());
            if (sortKeys) {
                entries.sort((a, b) -> String.valueOf(a.getKey()).compareTo(String.valueOf(b.getKey())));
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : entries) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue(), sortKeys);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object item : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, item, sortKeys);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value: " + v.getClass());
        }
    }

    /** Python string literal escaping with ensure_ascii=True. */
    static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c >= 0x20 && c <= 0x7e) {
                        sb.append(c);
                    } else {                               // control, DEL and non-ASCII (UTF-16 units)
                        sb.append("\\u").append(HEX[(c >> 12) & 0xF]).append(HEX[(c >> 8) & 0xF])
                                .append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                    }
                }
            }
        }
        sb.append('"');
    }

    /**
     * Python's {@code float.__repr__}: shortest round-trip digits (same as
     * {@link Double#toString} since JDK 19), fixed notation for exponents in [-4, 16)
     * and {@code 1e+16} / {@code 1e-05} style otherwise.
     */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        if (d == 0) {
            return (1 / d < 0) ? "-0.0" : "0.0";
        }
        String sign = d < 0 ? "-" : "";
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        String digits = bd.unscaledValue().toString();
        int exp = digits.length() - 1 - bd.scale();
        if (exp >= -4 && exp < 16) {
            String plain = bd.toPlainString();
            return sign + (plain.indexOf('.') < 0 ? plain + ".0" : plain);
        }
        String mantissa = digits.length() > 1 ? digits.charAt(0) + "." + digits.substring(1) : digits;
        int a = Math.abs(exp);
        return sign + mantissa + "e" + (exp < 0 ? "-" : "+") + (a < 10 ? "0" + a : String.valueOf(a));
    }
}
