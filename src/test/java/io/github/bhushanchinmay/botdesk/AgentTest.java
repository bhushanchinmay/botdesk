package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.CodingEngine;
import io.github.bhushanchinmay.botdesk.agent.ToolLoopAgent;
import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.agent.tool.Tool;
import io.github.bhushanchinmay.botdesk.agent.tool.ToolRegistry;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.store.JsonFileStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static io.github.bhushanchinmay.botdesk.Fixtures.FakeLlm.call;
import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class AgentTest {

    public static void testOldToolResultsAreTrimmedToSaveTokens() {
        Path dir = Fixtures.tempDir();
        Project p = Fixtures.gitProject(dir, "");
        Fixtures.sh(p.repo(), "head -c 3000 /dev/zero | tr '\\0' x > src/big.txt");
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm();
        for (int i = 0; i < 9; i++) llm.tools("engineer", call("read_file", "path", "src/big.txt"));
        llm.tools("engineer", call("finish", "summary", "done"));

        new ToolLoopAgent(llm, new JsonFileStore(dir.resolve("data"))).run(new CodingEngine.Job(1, "engineer", "rules", "task",
                new Workspace(p.repo(), p, Duration.ofSeconds(5)), 20));

        List<Map<String, Object>> last = llm.requests.get(llm.requests.size() - 1).messages();
        // The fake keeps a live reference to the conversation, so drop the final "ok" from finish.
        List<String> results = last.stream().filter(m -> "tool".equals(m.get("role")))
                .map(m -> (String) m.get("content")).filter(c -> !c.equals("ok")).toList();
        eq(9, results.size(), "nine tool results in the final call");
        long trimmed = results.stream().filter(c -> c.startsWith("[older result trimmed")).count();
        eq(3L, trimmed, "all but the last 6 large results trimmed");
        check(results.get(8).length() >= 3000, "the newest result is kept in full");
    }

    public static void testCustomToolPlugsIn() {
        Path dir = Fixtures.tempDir();
        Project p = Fixtures.gitProject(dir, "");
        Tool wordCount = new Tool() {
            public String name() { return "word_count"; }
            public String description(Workspace ws) { return "Count words in a file"; }
            public Map<String, String> parameters() { return Map.of("path", "File path"); }
            public String execute(Map<String, String> args, Workspace ws) throws java.io.IOException {
                return String.valueOf(ws.readFile(args.get("path")).trim().split("\\s+").length);
            }
        };
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm()
                .tools("engineer", call("word_count", "path", "src/calc.txt"))
                .tools("engineer", call("finish", "summary", "done"));
        new ToolLoopAgent(llm, new JsonFileStore(dir.resolve("data")), ToolRegistry.builtIn().with(wordCount))
                .run(new CodingEngine.Job(1, "engineer", "rules", "task", new Workspace(p.repo(), p, Duration.ofSeconds(5)), 5));

        Object firstResult = llm.requests.get(1).messages().stream().filter(m -> "tool".equals(m.get("role")))
                .findFirst().orElseThrow().get("content");
        eq("6", firstResult, "custom tool ran through the sandboxed workspace");
        check(llm.requests.get(0).tools().toString().contains("word_count"), "custom tool offered to the model");
    }
}
