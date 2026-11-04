package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.project.Registry;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class ConfigTest {

    private static String loadError(String json) throws Exception {
        Path file = Fixtures.tempDir().resolve("botdesk.json");
        Files.writeString(file, json);
        try {
            Config.load(file);
            return "";
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
    }

    // The shipped files must pass the same strict checks a user's file does.
    public static void testShippedFilesAreValid() throws Exception {
        Config c = Config.load(Path.of("botdesk.json"));
        check(c.roles().containsKey("engineer"), "roles load");
        check(Registry.load(Path.of("projects"), Path.of("playbooks")).playbook("bugfix").isPresent(), "playbooks load");
    }

    public static void testCommentsAreAllowed() {
        var m = Json.parseObject("""
                {
                  // the dashboard port
                  "port": 9090, // inline comment
                  "url": "https://example.com/v1"
                }""");
        eq(9090L, ((Number) m.get("port")).longValue(), "value after a comment");
        eq("https://example.com/v1", m.get("url"), "// inside a string is kept");
    }

    public static void testTypoIsAStartupError() throws Exception {
        String err = loadError("{ \"reviewPassScor\": 9 }");
        check(err.contains("botdesk.json: Unknown key 'reviewPassScor'") && err.contains("reviewPassScore"), err);
        check(loadError("{ \"slack\": { \"allowedUsers\": [] } }").contains("in slack"), "nested object");
    }

    public static void testNumbersAreRangeChecked() throws Exception {
        check(loadError("{ \"reviewPassScore\": 80 }").contains("from 1 to 10"), "range");
        check(loadError("{ \"server\": { \"port\": \"8080\" } }").contains("whole number"), "wrong type");
        check(loadError("{ \"maxRepairCycles\": 1.5 }").contains("whole number"), "not whole");
        eq("", loadError("{ \"reviewPassScore\": 9 }"), "valid value loads");
    }
}
