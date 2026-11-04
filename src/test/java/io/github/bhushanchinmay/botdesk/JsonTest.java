package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.json.Json;

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
}
