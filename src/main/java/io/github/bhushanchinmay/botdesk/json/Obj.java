package io.github.bhushanchinmay.botdesk.json;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Typed reads from parsed JSON maps, with defaults. */
public final class Obj {

    private Obj() {}

    public static String str(Map<String, Object> m, String key, String fallback) {
        Object v = m.get(key);
        return v == null ? fallback : v.toString();
    }

    public static long num(Map<String, Object> m, String key, long fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.longValue() : fallback;
    }

    public static double dbl(Map<String, Object> m, String key, double fallback) {
        Object v = m.get(key);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
        Object v = m.get(key);
        return v instanceof Boolean b ? b : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    public static List<String> strings(Map<String, Object> m, String key) {
        return list(m, key).stream().map(String::valueOf).toList();
    }

    /**
     * Config files are read with defaults, so a typo like "reviewPassScor" would silently use the
     * default. Call this on every object read from a config file to turn that into a startup error.
     */
    public static void requireKnownKeys(Map<String, Object> m, String where, Set<String> known) {
        for (String key : m.keySet()) {
            if (!known.contains(key)) {
                throw new IllegalArgumentException("Unknown key '" + key + "' in " + where
                        + ". Known keys: " + String.join(", ", new TreeSet<>(known)));
            }
        }
    }

    /** A whole number in [min, max], or the fallback when absent. Wrong type or range fails. */
    public static int intIn(Map<String, Object> m, String key, int fallback, int min, int max) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue())
                || n.doubleValue() < min || n.doubleValue() > max) {
            throw new IllegalArgumentException("'" + key + "' must be a whole number from " + min + " to " + max
                    + ", got " + v);
        }
        return n.intValue();
    }

    /** A number in [min, max], or the fallback when absent. Wrong type or range fails. */
    public static double dblIn(Map<String, Object> m, String key, double fallback, double min, double max) {
        Object v = m.get(key);
        if (v == null) return fallback;
        if (!(v instanceof Number n) || n.doubleValue() < min || n.doubleValue() > max) {
            throw new IllegalArgumentException("'" + key + "' must be a number from " + min + " to " + max
                    + ", got " + v);
        }
        return n.doubleValue();
    }
}
