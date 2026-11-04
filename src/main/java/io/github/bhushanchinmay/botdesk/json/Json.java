package io.github.bhushanchinmay.botdesk.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer so the project needs no external libraries.
 * Objects become LinkedHashMap, arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {

    private Json() {}

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != text.length()) throw p.error("Trailing characters");
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map)) throw new IllegalArgumentException("Expected a JSON object");
        return (Map<String, Object>) value;
    }

    /** LLMs often wrap JSON in prose or code fences. Pull out the first balanced {...} block. */
    public static Map<String, Object> extractObject(String text) {
        int start = text.indexOf('{');
        while (start >= 0) {
            int end = findBalancedEnd(text, start);
            if (end > start) {
                try {
                    return parseObject(text.substring(start, end + 1));
                } catch (IllegalArgumentException ignored) {
                    // try the next opening brace
                }
            }
            start = text.indexOf('{', start + 1);
        }
        throw new IllegalArgumentException("No JSON object found in model output");
    }

    private static int findBalancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 2, 0);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) sb.append("null");
        else if (v instanceof String s) writeString(sb, s);
        else if (v instanceof Number || v instanceof Boolean) sb.append(v);
        else if (v instanceof Enum<?> e) writeString(sb, e.name());
        else if (v instanceof Map<?, ?> m) writeObject(sb, m, indent, level);
        else if (v instanceof Iterable<?> list) writeArray(sb, list, indent, level);
        else writeString(sb, v.toString());
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent, int level) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, level + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(indent > 0 ? ": " : ":");
            writeValue(sb, e.getValue(), indent, level + 1);
        }
        if (!m.isEmpty()) newline(sb, indent, level);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> list, int indent, int level) {
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) sb.append(',');
            first = false;
            newline(sb, indent, level + 1);
            writeValue(sb, item, indent, level + 1);
        }
        if (!first) newline(sb, indent, level);
        sb.append(']');
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent > 0) sb.append('\n').append(" ".repeat(indent * level));
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) { this.s = s; }

        Object readValue() {
            if (pos >= s.length()) throw error("Unexpected end");
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                map.put(key, readValue());
                skipWhitespace();
                if (peek() == ',') { pos++; continue; }
                expect('}');
                return map;
            }
        }

        List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                skipWhitespace();
                list.add(readValue());
                skipWhitespace();
                if (peek() == ',') { pos++; continue; }
                expect(']');
                return list;
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> { sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16)); pos += 4; }
                    default -> sb.append(e);
                }
            }
            throw error("Unterminated string");
        }

        Object readNumber() {
            int start = pos;
            while (pos < s.length() && "+-0123456789.eE".indexOf(s.charAt(pos)) >= 0) pos++;
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw error("Unexpected character");
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            return Long.parseLong(num);
        }

        Object readLiteral(String word, Object value) {
            if (!s.startsWith(word, pos)) throw error("Bad literal");
            pos += word.length();
            return value;
        }

        void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) pos++;
        }

        char peek() {
            if (pos >= s.length()) throw error("Unexpected end");
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) throw error("Expected '" + c + "'");
            pos++;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException(msg + " at position " + pos);
        }
    }
}
