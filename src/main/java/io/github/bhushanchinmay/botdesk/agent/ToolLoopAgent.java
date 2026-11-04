package io.github.bhushanchinmay.botdesk.agent;

import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A minimal coding agent: the model looks at the code, calls a tool, sees the result, and repeats.
 *
 *   loop up to maxSteps:
 *     ask the model (conversation so far + tool definitions)
 *     no tool calls?        -> done, its text is the summary
 *     for each tool call:   run it on the Workspace, append the result
 *     called finish?        -> done
 *
 * Tool errors are returned to the model as text, never thrown, so it can correct itself.
 * Every tool call is logged to the task timeline, so you can replay what the agent did.
 */
public final class ToolLoopAgent implements CodingEngine {

    private static final int MAX_RESULT_CHARS = 12_000;

    private final LlmClient llm;
    private final Store store;

    public ToolLoopAgent(LlmClient llm, Store store) {
        this.llm = llm;
        this.store = store;
    }

    @Override
    public Outcome run(Job job) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(LlmClient.system(job.instructions()));
        messages.add(LlmClient.user(job.assignment()));
        List<Map<String, Object>> tools = toolDefinitions(job.workspace());

        for (int step = 1; step <= job.maxSteps(); step++) {
            LlmClient.Result reply = llm.complete(new LlmClient.Request(job.taskId(), job.role(), messages, tools));
            messages.add(reply.assistantMessage());
            if (reply.toolCalls().isEmpty()) {
                return new Outcome(reply.text(), step, true);
            }
            String finishSummary = null;
            for (LlmClient.ToolCall call : reply.toolCalls()) {
                if (call.name().equals("finish")) {
                    finishSummary = arg(call, "summary");
                    messages.add(LlmClient.toolResult(call.id(), "ok"));
                    continue;
                }
                String result = execute(job.workspace(), call);
                store.addEvent(job.taskId(), job.role(), "tool", describe(call) + " -> " + firstLine(result));
                messages.add(LlmClient.toolResult(call.id(), clip(result)));
            }
            if (finishSummary != null) return new Outcome(finishSummary, step, true);
        }
        return new Outcome("Stopped after " + job.maxSteps() + " steps without calling finish.", job.maxSteps(), false);
    }

    // ---------- tool dispatch ----------

    private String execute(Workspace ws, LlmClient.ToolCall call) {
        try {
            return switch (call.name()) {
                case "list_files" -> ws.listFiles(arg(call, "path"));
                case "read_file" -> ws.readFile(arg(call, "path"));
                case "search" -> ws.search(arg(call, "regex"));
                case "write_file" -> ws.writeFile(arg(call, "path"), arg(call, "content"));
                case "replace_in_file" -> ws.replaceInFile(arg(call, "path"), arg(call, "old_text"), arg(call, "new_text"));
                case "run_check" -> {
                    Workspace.CheckResult r = ws.runCheck(arg(call, "name"));
                    yield (r.passed() ? "PASSED" : r.timedOut() ? "TIMED OUT" : "FAILED (exit " + r.exitCode() + ")")
                            + "\n" + r.output();
                }
                default -> "Unknown tool: " + call.name();
            };
        } catch (SecurityException e) {
            return "REFUSED: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    private static String arg(LlmClient.ToolCall call, String name) {
        try {
            return Obj.str(Json.parseObject(call.argumentsJson()), name, "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ---------- tool definitions (OpenAI function-calling format) ----------

    private static List<Map<String, Object>> toolDefinitions(Workspace ws) {
        return List.of(
                tool("list_files", "List files under a folder of the repo (relative path, '.' for root).",
                        Map.of("path", "Folder, relative to repo root")),
                tool("read_file", "Read a text file.", Map.of("path", "File path relative to repo root")),
                tool("search", "Search all text files with a Java regex. Returns file:line: text.",
                        Map.of("regex", "Java regular expression")),
                tool("replace_in_file", "Replace one exact, unique snippet in a file. Prefer this for edits.",
                        Map.of("path", "File path", "old_text", "Exact existing text, unique in the file",
                                "new_text", "Replacement text")),
                tool("write_file", "Create or fully overwrite a file.",
                        Map.of("path", "File path", "content", "Full file content")),
                tool("run_check", "Run one of the project's checks. Available: " + ws.checkNames(),
                        Map.of("name", "Check name, one of " + ws.checkNames())),
                tool("finish", "Call when the work is done and checks pass, or when you are blocked.",
                        Map.of("summary", "What you changed and why, and any blockers. 3-6 lines.")));
    }

    private static Map<String, Object> tool(String name, String description, Map<String, String> params) {
        Map<String, Object> props = new java.util.LinkedHashMap<>();
        params.forEach((k, v) -> props.put(k, Map.of("type", "string", "description", v)));
        return Map.of("type", "function", "function", Map.of(
                "name", name, "description", description,
                "parameters", Map.of("type", "object", "properties", props, "required", List.copyOf(params.keySet()))));
    }

    // ---------- helpers ----------

    private static String describe(LlmClient.ToolCall call) {
        String path = arg(call, "path");
        String name = arg(call, "name");
        String detail = !path.isEmpty() ? path : !name.isEmpty() ? name : arg(call, "regex");
        return call.name() + (detail.isEmpty() ? "" : " " + detail);
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        String line = nl < 0 ? s : s.substring(0, nl);
        return line.length() > 120 ? line.substring(0, 120) + "..." : line;
    }

    private static String clip(String s) {
        return s.length() <= MAX_RESULT_CHARS ? s : s.substring(0, MAX_RESULT_CHARS) + "\n...(cut)";
    }
}
