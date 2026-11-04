package io.github.bhushanchinmay.botdesk.config;

import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;

import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Checks every role before any task runs: is the key set, does the provider accept it,
 * does the model exist, and can the engineer model call tools?
 * Uses only GET /models, so it costs nothing. Run with: ./scripts/run-local.sh --check
 */
public final class SetupCheck {

    private final Config config;
    private final UnaryOperator<String> env;
    private final HttpClient http;
    private final PrintStream out;

    public SetupCheck(Config config, UnaryOperator<String> env, HttpClient http, PrintStream out) {
        this.config = config;
        this.env = env;
        this.http = http;
        this.out = out;
    }

    /** Returns true when nothing failed. Warnings do not fail the check. */
    public boolean run() {
        boolean ok = true;
        for (Config.Role role : config.roles().values()) {
            for (String line : checkRole(role)) {
                out.println(line);
                if (line.startsWith("FAIL")) ok = false;
            }
        }
        out.println(ok ? "\nAll roles ready." : "\nFix the FAIL lines, restart botdesk, then click Approve on the failed task to retry.");
        return ok;
    }

    List<String> checkRole(Config.Role role) {
        List<String> lines = new ArrayList<>();
        String who = role.name() + " (" + role.provider() + " / " + role.model() + ")";
        Config.Provider provider = config.provider(role.provider());

        if (role.model().endsWith(":batch")) {
            lines.add("FAIL " + who + ": ':batch' models answer asynchronously through a Batch API, up to 24 hours later. "
                    + "botdesk needs an answer in seconds. Remove ':batch' (or use ':floor' for a cheaper synchronous price).");
            return lines;
        }
        String key = env.apply(provider.apiKeyEnv());
        if (key == null || key.isBlank()) {
            lines.add("FAIL " + who + ": " + provider.apiKeyEnv() + " is not set. Save it in the Keychain: "
                    + "security add-generic-password -U -s botdesk -a " + provider.apiKeyEnv() + " -w");
            return lines;
        }
        if (!key.equals(key.strip())) {
            lines.add("FAIL " + who + ": " + provider.apiKeyEnv() + " has spaces or a line break around it. Save it again.");
            return lines;
        }

        HttpResponse<String> res;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/models"))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + key).GET();
            provider.headers().forEach(b::header);
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            lines.add("FAIL " + who + ": cannot reach " + provider.baseUrl() + " (" + e.getMessage() + ")");
            return lines;
        }
        if (res.statusCode() == 401 || res.statusCode() == 403) {
            lines.add("FAIL " + who + ": the provider rejected the key in " + provider.apiKeyEnv()
                    + " (HTTP " + res.statusCode() + "). It starts with '" + key.substring(0, Math.min(4, key.length()))
                    + "'. Create a new key in the provider's dashboard and save it again.");
            return lines;
        }
        if (res.statusCode() != 200) {
            lines.add("WARN " + who + ": key accepted, but GET /models returned HTTP " + res.statusCode()
                    + ", so the model name could not be checked.");
            return lines;
        }

        Map<String, Object> model = findModel(res.body(), role.model());
        if (model == null) {
            lines.add("FAIL " + who + ": key OK, but the provider does not list model '" + role.model()
                    + "'. Copy the exact ID from the provider's model list." + suggestions(res.body(), role.model()));
            return lines;
        }
        List<String> params = Obj.strings(model, "supported_parameters");
        if (role.name().equals("engineer") && !params.isEmpty() && !params.contains("tools")) {
            lines.add("FAIL " + who + ": key and model OK, but this model does not support tool calling. "
                    + "The engineer needs tools. Pick a model that lists 'tools'.");
            return lines;
        }
        lines.add("OK   " + who + ": key accepted, model found"
                + (role.name().equals("engineer") && params.isEmpty() ? " (provider does not say if it supports tools; the first task will tell)" : ""));
        return lines;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findModel(String body, String id) {
        try {
            for (Object o : Obj.list(Json.parseObject(body), "data")) {
                Map<String, Object> m = (Map<String, Object>) o;
                if (id.equals(Obj.str(m, "id", ""))) return m;
            }
        } catch (IllegalArgumentException ignored) {
            // not a standard model list
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String suggestions(String body, String id) {
        String stem = id.replaceAll("[-:].*$", "").toLowerCase();
        List<String> close = new ArrayList<>();
        try {
            for (Object o : Obj.list(Json.parseObject(body), "data")) {
                String other = Obj.str((Map<String, Object>) o, "id", "");
                if (!stem.isEmpty() && other.toLowerCase().contains(stem) && close.size() < 5) close.add(other);
            }
        } catch (IllegalArgumentException ignored) {
            // no suggestions
        }
        return close.isEmpty() ? "" : " Similar: " + String.join(", ", close);
    }
}
