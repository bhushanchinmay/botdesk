package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.domain.Records.ModelCall;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.llm.OpenAiCompatibleClient;
import io.github.bhushanchinmay.botdesk.store.JsonFileStore;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;
import static io.github.bhushanchinmay.botdesk.TestRunner.throwsAny;

public final class OpenAiClientTest {

    private static final String TEXT = """
            {"model":"mid-model-2026","choices":[{"message":{"role":"assistant","content":"hello"}}],
             "usage":{"prompt_tokens":100,"completion_tokens":50,"cost":0.0123}}""";

    private static final String TOOL_CALL = """
            {"model":"mid-model","choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
              {"id":"call_1","type":"function","function":{"name":"read_file","arguments":"{\\"path\\":\\"src/a.txt\\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    private static OpenAiCompatibleClient client(Path dir, String url, double budget, Store store) {
        return new OpenAiCompatibleClient(Fixtures.config(dir, url, budget), store, HttpClient.newHttpClient(),
                name -> name.equals("STUB_KEY") ? "sk-test" : null, Duration.ZERO);
    }

    private static LlmClient.Request ask(long taskId, String role) {
        return new LlmClient.Request(taskId, role, List.of(LlmClient.user("hi")));
    }

    public static void testTextReplyAndProviderCost() throws Exception {
        Path dir = Fixtures.tempDir();
        Store store = new JsonFileStore(dir.resolve("data"));
        try (Fixtures.StubServer stub = new Fixtures.StubServer().on("/chat/completions", 200, TEXT)) {
            LlmClient.Result r = client(dir, stub.url(), 10, store).complete(ask(7, "engineer"));
            eq("hello", r.text(), "text");
            eq(0.0123, r.costUsd(), "provider-reported cost wins");
            check(stub.bodies.get(0).contains("\"model\":\"mid-model\""), "role resolved to model");
            eq(List.of("Bearer sk-test"), stub.headers.get(0).get("Authorization"), "auth header");
            eq(1, store.calls(7).size(), "call recorded for the task");
        }
    }

    public static void testParsesToolCallsAndSendsTools() throws Exception {
        Path dir = Fixtures.tempDir();
        try (Fixtures.StubServer stub = new Fixtures.StubServer().on("/chat/completions", 200, TOOL_CALL)) {
            List<Map<String, Object>> tools = List.of(Map.of("type", "function", "function", Map.of("name", "read_file")));
            LlmClient.Result r = client(dir, stub.url(), 10, new JsonFileStore(dir.resolve("data")))
                    .complete(new LlmClient.Request(0, "engineer", List.of(LlmClient.user("go")), tools));
            eq(1, r.toolCalls().size(), "one tool call");
            eq("read_file", r.toolCalls().get(0).name(), "tool name");
            eq("{\"path\":\"src/a.txt\"}", r.toolCalls().get(0).argumentsJson(), "arguments");
            check(r.assistantMessage().containsKey("tool_calls"), "assistant message keeps tool_calls for the next turn");
            eq("", r.assistantMessage().get("content"), "null content becomes empty string");
            check(stub.bodies.get(0).contains("\"tools\":[") && stub.bodies.get(0).contains("read_file"), "tools sent");
            // Engineer price: $3/M in, $15/M out -> 10*3/1e6 + 5*15/1e6
            eq(0.000105, Math.round(r.costUsd() * 1e9) / 1e9, "estimated cost when provider reports none");
        }
    }

    public static void testRetriesServerErrorsButNotClientErrors() throws Exception {
        Path dir = Fixtures.tempDir();
        try (Fixtures.StubServer stub = new Fixtures.StubServer()
                .on("/chat/completions", 503, "busy").on("/chat/completions", 200, TEXT)) {
            eq("hello", client(dir, stub.url(), 10, new JsonFileStore(dir.resolve("data"))).complete(ask(0, "lead")).text(), "retried");
            eq(2, stub.bodies.size(), "two attempts");
        }
        try (Fixtures.StubServer stub = new Fixtures.StubServer().on("/chat/completions", 401, "bad key")) {
            OpenAiCompatibleClient c = client(dir, stub.url(), 10, new JsonFileStore(dir.resolve("data2")));
            throwsAny(() -> c.complete(ask(0, "lead")), "401 fails");
            eq(1, stub.bodies.size(), "no retry on 401");
        }
    }

    public static void testBudgetGuardBlocksBeforeCalling() throws Exception {
        Path dir = Fixtures.tempDir();
        Store store = new JsonFileStore(dir.resolve("data"));
        store.recordCall(new ModelCall(0, "lead", "stub", "m", 0, 0, 5.0, Instant.now()));
        try (Fixtures.StubServer stub = new Fixtures.StubServer().on("/chat/completions", 200, TEXT)) {
            try {
                client(dir, stub.url(), 5.0, store).complete(ask(0, "engineer"));
                throw new AssertionError("expected budget exception");
            } catch (LlmClient.BudgetExceededException expected) {
                eq(0, stub.bodies.size(), "no request sent");
            }
        }
    }
}
