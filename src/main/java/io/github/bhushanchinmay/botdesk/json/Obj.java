package io.github.bhushanchinmay.botdesk.json;

import java.util.List;
import java.util.Map;

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
}
