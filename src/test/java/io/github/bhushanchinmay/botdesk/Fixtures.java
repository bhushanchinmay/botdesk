package io.github.bhushanchinmay.botdesk;

import com.sun.net.httpserver.HttpServer;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.project.Registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared test helpers: temp dirs, a real git repo, config, a scripted fake LLM, a stub HTTP server. */
final class Fixtures {

    private Fixtures() {}

    static Path tempDir() {
        try {
            return Files.createTempDirectory("botdesk-test").toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Config config(Path dir, String baseUrl, double monthlyBudget) {
        Map<String, Config.Provider> providers = Map.of("stub",
                new Config.Provider("stub", baseUrl, "STUB_KEY", Map.of("X-Test", "1"), Map.of()));
        Map<String, Config.Role> roles = Map.of(
                "lead", new Config.Role("lead", "stub", "big-model", 10, 30),
                "engineer", new Config.Role("engineer", "stub", "mid-model", 3, 15),
                "reviewer", new Config.Role("reviewer", "stub", "other-model", 1, 2));
        return new Config("127.0.0.1", 0, ZoneId.of("Asia/Kolkata"), dir.resolve("data"), dir.resolve("projects"),
                dir.resolve("playbooks"), dir.resolve("data/workspaces"), 1, 2, 10, 8, 30, monthlyBudget, 1.0,
                providers, roles, new Config.GitIdentity("Chinmay Bhushan", "21293962+bhushanchinmay@users.noreply.github.com"),
                "GITHUB_TOKEN", List.of(), new Config.Slack(false, "", "", "", List.of()), List.of());
    }

    /**
     * A tiny real git repo. The "app" is src/calc.txt; the test check passes when it says "a + b"
     * and a test file exists. It starts buggy ("a - b").
     */
    static Project gitProject(Path dir, String githubRepo) {
        Path repo = dir.resolve("repo");
        try {
            Files.createDirectories(repo.resolve("src"));
            Files.createDirectories(repo.resolve("tests"));
            Files.writeString(repo.resolve("src/calc.txt"), "add(a, b) = a - b\n");
            Files.writeString(repo.resolve("tests/existing_test.txt"), "existing test\n");
            Files.writeString(repo.resolve("README.md"), "# calc\nAdds numbers.\n");
            Files.writeString(repo.resolve("test.sh"), "grep -q 'a + b' src/calc.txt && test -f tests/add_test.txt\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        sh(repo, "git init -q -b main && git add -A && git -c user.name=t -c user.email=t@t commit -q -m init");
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("build", "test -f src/calc.txt");
        checks.put("test", "bash test.sh");
        return new Project("calc", repo, "main", checks, List.of("README.md"), List.of("tests/"),
                List.of("test.sh"), githubRepo, "origin");
    }

    static Registry registry(Project project) {
        Map<String, Playbook> playbooks = Map.of(
                "bugfix", new Playbook("bugfix", "Fix a bug", "plan it", "fix it", "is it right", true, false, false, false),
                "investigate", new Playbook("investigate", "Answer a question", "plan", "read only", "is it right", false, true, true, false),
                "maintenance", new Playbook("maintenance", "Keep the build healthy", "plan", "fix it", "is it right", false, true, false, true));
        return new Registry(Map.of(project.name(), project), playbooks);
    }

    static String sh(Path dir, String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (p.waitFor() != 0) throw new IllegalStateException(command + " failed: " + out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- scripted fake LLM ----------

    /** Replies from a per-role script, in order. Records every request it receives. */
    static final class FakeLlm implements LlmClient {
        private final Map<String, Deque<Result>> scripts = new HashMap<>();
        private final Set<String> interruptRoles = new HashSet<>();
        final List<Request> requests = new ArrayList<>();

        /** Simulates shutdown: calls for this role interrupt the thread, as the real client would see. */
        FakeLlm interruptOn(String role) {
            interruptRoles.add(role);
            return this;
        }

        FakeLlm text(String role, String... texts) {
            for (String t : texts) script(role).add(result(t, List.of()));
            return this;
        }

        /** One model turn that calls tools. Each call is name + key/value args. */
        FakeLlm tools(String role, ToolCall... calls) {
            script(role).add(result("", List.of(calls)));
            return this;
        }

        static ToolCall call(String name, String... kv) {
            Map<String, Object> args = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) args.put(kv[i], kv[i + 1]);
            return new ToolCall("call_" + name + "_" + System.nanoTime(), name, Json.write(args));
        }

        private Deque<Result> script(String role) { return scripts.computeIfAbsent(role, r -> new ArrayDeque<>()); }

        private static Result result(String text, List<ToolCall> calls) {
            Map<String, Object> msg = new LinkedHashMap<>(LlmClient.assistant(text));
            return new Result(text, calls, msg, "fake", "fake-model", 10, 5, 0.001);
        }

        @Override
        public Result complete(Request request) {
            requests.add(request);
            if (interruptRoles.contains(request.role())) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted");
            }
            Deque<Result> script = scripts.get(request.role());
            if (script == null || script.isEmpty()) throw new LlmException("No scripted reply for " + request.role());
            return script.poll();
        }

        int calls(String role) { return (int) requests.stream().filter(r -> r.role().equals(role)).count(); }
    }

    // ---------- stub HTTP server ----------

    /** Local HTTP server. Each path returns its scripted responses in order; the last one repeats. */
    static final class StubServer implements AutoCloseable {
        final HttpServer server;
        final List<String> bodies = new ArrayList<>();
        final List<Map<String, List<String>>> headers = new ArrayList<>();
        private final Map<String, Deque<Object[]>> responses = new HashMap<>();

        StubServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", ex -> {
                bodies.add(new String(ex.getRequestBody().readAllBytes()));
                headers.add(Map.copyOf(ex.getRequestHeaders()));
                Deque<Object[]> q = responses.get(ex.getRequestURI().getPath());
                Object[] r = q == null || q.isEmpty() ? new Object[]{404, "not scripted"} : q.size() > 1 ? q.poll() : q.peek();
                byte[] bytes = ((String) r[1]).getBytes();
                ex.sendResponseHeaders((int) r[0], bytes.length);
                ex.getResponseBody().write(bytes);
                ex.close();
            });
            server.start();
        }

        StubServer on(String path, int status, String body) {
            responses.computeIfAbsent(path, p -> new ArrayDeque<>()).add(new Object[]{status, body});
            return this;
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

        @Override
        public void close() { server.stop(0); }
    }
}
