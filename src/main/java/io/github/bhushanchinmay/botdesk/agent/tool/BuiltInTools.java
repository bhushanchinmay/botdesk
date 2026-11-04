package io.github.bhushanchinmay.botdesk.agent.tool;

import io.github.bhushanchinmay.botdesk.agent.Workspace;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tools botdesk ships with. Each is a thin adapter from model arguments to one Workspace method.
 * Deliberately absent: a free-form shell, network access, and git commands (see docs/adr/0005).
 */
public final class BuiltInTools {

    private BuiltInTools() {}

    public static List<Tool> all() {
        return List.of(
                simple("list_files", "List files under a folder of the repo (relative path, '.' for root).",
                        params("path", "Folder, relative to repo root"),
                        (a, ws) -> ws.listFiles(a.get("path"))),
                simple("read_file", "Read a text file.",
                        params("path", "File path relative to repo root"),
                        (a, ws) -> ws.readFile(a.get("path"))),
                simple("search", "Search all text files with a Java regex. Returns file:line: text.",
                        params("regex", "Java regular expression"),
                        (a, ws) -> ws.search(a.get("regex"))),
                simple("replace_in_file", "Replace one exact, unique snippet in a file. Prefer this for edits.",
                        params("path", "File path", "old_text", "Exact existing text, unique in the file",
                                "new_text", "Replacement text"),
                        (a, ws) -> ws.replaceInFile(a.get("path"), a.get("old_text"), a.get("new_text"))),
                simple("write_file", "Create or fully overwrite a file.",
                        params("path", "File path", "content", "Full file content"),
                        (a, ws) -> ws.writeFile(a.get("path"), a.get("content"))),
                new RunCheckTool());
    }

    /** Lets most tools be one line: name, description, parameters, and what to call. */
    @FunctionalInterface
    interface Handler {
        String apply(Map<String, String> args, Workspace workspace) throws IOException;
    }

    private record SimpleTool(String name, String fixedDescription, Map<String, String> parameters, Handler handler)
            implements Tool {
        @Override
        public String description(Workspace workspace) { return fixedDescription; }

        @Override
        public String execute(Map<String, String> args, Workspace workspace) throws IOException {
            return handler.apply(args, workspace);
        }
    }

    private static Tool simple(String name, String description, Map<String, String> params, Handler handler) {
        return new SimpleTool(name, description, params, handler);
    }

    private static Map<String, String> params(String... nameThenDescription) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenDescription.length; i += 2) m.put(nameThenDescription[i], nameThenDescription[i + 1]);
        return m;
    }

    /** Runs one named project check. Its description lists the checks, so the model knows its options. */
    private static final class RunCheckTool implements Tool {
        @Override
        public String name() { return "run_check"; }

        @Override
        public String description(Workspace ws) { return "Run one of the project's checks. Available: " + ws.checkNames(); }

        @Override
        public Map<String, String> parameters() { return params("name", "Check name"); }

        @Override
        public String execute(Map<String, String> args, Workspace ws) {
            Workspace.CheckResult r = ws.runCheck(args.get("name"));
            String verdict = r.passed() ? "PASSED" : r.timedOut() ? "TIMED OUT" : "FAILED (exit " + r.exitCode() + ")";
            return verdict + "\n" + r.output();
        }
    }
}
