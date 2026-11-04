package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.json.Json;

import java.util.List;
import java.util.Map;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;
import static io.github.bhushanchinmay.botdesk.TestRunner.throwsAny;

public final class JsonTest {

    public static void testRoundTrip() {
        String text = "{\"a\":1,\"b\":[true,null,2.5],\"c\":{\"d\":\"x\\\"y\\n\"}}";
        eq(text, Json.write(Json.parseObject(text)), "round trip");
    }

    public static void testExtractFromFencedProse() {
        Map<String, Object> m = Json.extractObject("Sure!\n```json\n{\"score\": 9, \"note\": \"use {braces} ok\"}\n```");
        eq(9L, m.get("score"), "score");
        eq("use {braces} ok", m.get("note"), "braces inside strings");
    }

    public static void testRejectsGarbage() {
        throwsAny(() -> Json.parse("{\"a\":}"), "bad json");
        throwsAny(() -> Json.extractObject("no json here"), "no object");
    }

    public static void testUnicodeEscape() {
        check(Json.parseObject("{\"s\":\"caf\\u00e9\"}").get("s").equals("café"), "unicode");
    }

    public static void testDeepNestingFailsCleanly() {
        String deep = "[".repeat(10_000) + "]".repeat(10_000);
        throwsAny(() -> Json.parse(deep), "deep nesting is rejected, not a StackOverflowError");
        eq(64, depthOf(Json.parse("[".repeat(64) + "]".repeat(64))), "64 levels still parse");
    }

    private static int depthOf(Object o) {
        int d = 0;
        while (o instanceof List<?> l) {
            d++;
            o = l.isEmpty() ? null : l.get(0);
        }
        return d;
    }
}
