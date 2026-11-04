package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.config.SetupCheck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class SetupCheckTest {

    private static final String MODELS = """
            {"data":[{"id":"glm-5.3-uncensored","supported_parameters":["tools","temperature"]},
                     {"id":"deepseek-v4.1-flash-uncensored","supported_parameters":["temperature"]},
                     {"id":"openai/gpt-6.1-terra"}]}""";

    private static Config config(String url, String lead, String engineer, String reviewer) {
        Map<String, Config.Role> roles = new LinkedHashMap<>();
        roles.put("lead", new Config.Role("lead", "p", lead, 0, 0));
        roles.put("engineer", new Config.Role("engineer", "p", engineer, 0, 0));
        roles.put("reviewer", new Config.Role("reviewer", "p", reviewer, 0, 0));
        return new Config("127.0.0.1", 0, ZoneId.of("UTC"), null, null, null, null, 1, 2, 10, 8, 30, 10, 1,
                Map.of("p", new Config.Provider("p", url, "P_KEY", Map.of(), Map.of())), roles,
                new Config.GitIdentity("a", "b"), "GITHUB_TOKEN", List.of(), new Config.Slack(false, "", "", ""));
    }

    private static String run(Config c, UnaryOperator<String> env, boolean[] ok) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        ok[0] = new SetupCheck(c, env, HttpClient.newHttpClient(), new PrintStream(buf)).run();
        return buf.toString();
    }

    public static void testAllGood() throws Exception {
        try (Fixtures.StubServer s = new Fixtures.StubServer().on("/models", 200, MODELS)) {
            boolean[] ok = new boolean[1];
            String out = run(config(s.url(), "deepseek-v4.1-flash-uncensored", "glm-5.3-uncensored", "openai/gpt-6.1-terra"),
                    k -> "xpl_good", ok);
            check(ok[0], "ready: " + out);
            eq(3, out.split("OK   ").length - 1, "three OK lines");
        }
    }

    public static void testRejectedKeyBatchModelAndMissingTools() throws Exception {
        try (Fixtures.StubServer s = new Fixtures.StubServer().on("/models", 401, "{\"error\":\"invalid_key\"}")) {
            boolean[] ok = new boolean[1];
            String out = run(config(s.url(), "x", "y", "openai/gpt-6.1-terra:batch"), k -> "xpl_bad", ok);
            check(!ok[0], "not ready");
            check(out.contains("rejected the key in P_KEY (HTTP 401). It starts with 'xpl_'"), "401 explained: " + out);
            check(out.contains("':batch' models answer asynchronously"), "batch explained");
        }
        try (Fixtures.StubServer s = new Fixtures.StubServer().on("/models", 200, MODELS)) {
            boolean[] ok = new boolean[1];
            String out = run(config(s.url(), "glm-5.3-uncensored", "deepseek-v4.1-flash-uncensored", "nope-model"), k -> "xpl_good", ok);
            check(out.contains("does not support tool calling"), "engineer without tools caught: " + out);
            check(out.contains("does not list model 'nope-model'"), "unknown model caught");
        }
    }

    public static void testMissingOrPaddedKey() {
        boolean[] ok = new boolean[1];
        Config c = config("http://127.0.0.1:1", "a", "b", "c");
        check(run(c, k -> null, ok).contains("P_KEY is not set"), "missing key");
        check(run(c, k -> "xpl_key\n", ok).contains("spaces or a line break"), "padded key");
    }
}
