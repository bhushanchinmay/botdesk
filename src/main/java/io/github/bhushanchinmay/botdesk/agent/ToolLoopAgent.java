package io.github.bhushanchinmay.botdesk.agent;

import io.github.bhushanchinmay.botdesk.agent.tool.Tool;
import io.github.bhushanchinmay.botdesk.agent.tool.ToolRegistry;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A minimal coding agent: the model looks at the code, calls a tool, sees the result, and repeats.
 *
 * <pre>
 *   loop up to maxSteps:
 *     ask the model (conversation so far + tool definitions)
 *     no tool calls?        -> done, its text is the summary
 *     for each tool call:   run it on the Workspace, append the result
 *     called finish?        -> done
 * </pre>
 *
 * Tool errors are returned to the model as text, never thrown, so it can correct itself.
 * Every tool call is logged to the task timeline, so you can replay what the agent did.
 */
public final class ToolLoopAgent implements CodingEngine {

    /** Control-flow tool: ends the loop. Not in the registry because it does not touch the workspace. */
    static final String FINISH = "finish";
    private static final int MAX_RESULT_CHARS = 12_000;
    private static final int MAX_LOG_CHARS = 120;
    /** Tool results older than this many are replaced by a one-line stub before the next model call. */
    static final int KEEP_RECENT_RESULTS = 6;
    private static final int STUB_THRESHOLD_CHARS = 400;

    private final LlmClient llm;
    private final Store store;
    private final ToolRegistry tools;

    public ToolLoopAgent(LlmClient llm, Store store) {
        this(llm, store, ToolRegistry.builtIn());
    }

    public ToolLoopAgent(LlmClient llm, Store store, ToolRegistry tools) {
        this.llm = Objects.requireNonNull(llm);
        this.store = Objects.requireNonNull(store);
        this.tools = Objects.requireNonNull(tools);
    }

    @Override
    public Outcome run(Job job) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(LlmClient.system(job.instructions()));
        messages.add(LlmClient.user(job.assignment()));
        List<Map<String, Object>> definitions = new ArrayList<>(tools.definitions(job.workspace()));
        definitions.add(ToolRegistry.definition(FINISH, "Call when the work is done and checks pass, or when you are blocked.",
                Map.of("summary", "What you changed and why, and any blockers. 3-6 lines.")));

        for (int step = 1; step <= job.maxSteps(); step++) {
            trimOldToolResults(messages);
            LlmClient.Result reply = llm.complete(new LlmClient.Request(job.taskId(), job.role(), messages, definitions));
            messages.add(reply.assistantMessage());
            if (reply.toolCalls().isEmpty()) {
                return new Outcome(reply.text(), step, true);
            }
            String finishSummary = null;
            for (LlmClient.ToolCall call : reply.toolCalls()) {
                Map<String, String> args = parseArgs(call);
                if (call.name().equals(FINISH)) {
                    finishSummary = args.getOrDefault("summary", "");
                    messages.add(LlmClient.toolResult(call.id(), "ok"));
                    continue;
                }
                String result = execute(job.workspace(), call.name(), args);
                store.addEvent(job.taskId(), job.role(), "tool", describe(call.name(), args) + " -> " + firstLine(result));
                messages.add(LlmClient.toolResult(call.id(), clip(result)));
            }
            if (finishSummary != null) return new Outcome(finishSummary, step, true);
        }
        return new Outcome("Stopped after " + job.maxSteps() + " steps without calling finish.", job.maxSteps(), false);
    }

    private String execute(Workspace ws, String name, Map<String, String> args) {
        Tool tool = tools.find(name).orElse(null);
        if (tool == null) return "Unknown tool: " + name + ". Available: " + tools.names();
        try {
            return tool.execute(args, ws);
        } catch (SecurityException e) {
            return "REFUSED: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Token saver: every step re-sends the whole conversation, so a file read at step 2 is paid for
     * again at steps 3..30. Keep the recent results in full; older large ones become a short stub.
     * The model can re-run a tool if it needs the content again.
     */
    static void trimOldToolResults(List<Map<String, Object>> messages) {
        List<Map<String, Object>> results = messages.stream().filter(m -> "tool".equals(m.get("role"))).toList();
        for (int i = 0; i < results.size() - KEEP_RECENT_RESULTS; i++) {
            Map<String, Object> m = results.get(i);
            String content = String.valueOf(m.get("content"));
            if (content.length() > STUB_THRESHOLD_CHARS) {
                m.put("content", "[older result trimmed to save tokens: " + content.length()
                        + " chars. Run the tool again if you still need it.]");
            }
        }
    }

    /** Models occasionally send invalid JSON; treat that as "no arguments" so the tool reports what is missing. */
    private static Map<String, String> parseArgs(LlmClient.ToolCall call) {
        Map<String, String> args = new LinkedHashMap<>();
        try {
            Json.parseObject(call.argumentsJson()).forEach((k, v) -> args.put(k, v == null ? "" : v.toString()));
        } catch (IllegalArgumentException ignored) {
            // fall through with no arguments
        }
        return args;
    }

    private static String describe(String name, Map<String, String> args) {
        String detail = args.getOrDefault("path", args.getOrDefault("name", args.getOrDefault("regex", "")));
        return detail.isEmpty() ? name : name + " " + detail;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > MAX_LOG_CHARS ? line.substring(0, MAX_LOG_CHARS) + "..." : line;
    }

    private static String clip(String s) {
        return s.length() <= MAX_RESULT_CHARS ? s : s.substring(0, MAX_RESULT_CHARS) + "\n...(cut)";
    }
}
