package io.github.bhushanchinmay.botdesk.agent;

import io.github.bhushanchinmay.botdesk.project.Project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Everything an agent can touch, and nothing more.
 *
 * Safety rules, enforced here in code:
 *  1. Every path must resolve inside the task's worktree (no "../", no symlink escapes, no .git).
 *  2. Protected paths (CI config, test runners) can be read but never written.
 *  3. The only commands are the project's named checks. There is no free-form shell.
 *  4. Every command has a timeout; every output is capped.
 */
public final class Workspace {

    private static final int MAX_READ_BYTES = 120_000;
    private static final int MAX_OUTPUT_CHARS = 6_000;
    private static final int MAX_LIST = 400;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    private final Path root;
    private final Project project;
    private final Duration checkTimeout;

    public record CheckResult(String name, int exitCode, boolean timedOut, String output) {
        public boolean passed() { return exitCode == 0 && !timedOut; }
    }

    public Workspace(Path root, Project project, Duration checkTimeout) {
        try {
            this.root = root.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException("Workspace missing: " + root, e);
        }
        this.project = project;
        this.checkTimeout = checkTimeout;
    }

    public Path root() { return root; }

    // ---------- rule 1: path confinement ----------

    Path resolve(String relative) {
        if (relative == null || relative.isBlank()) relative = ".";
        Path p = root.resolve(relative).normalize();
        if (!p.startsWith(root)) throw new SecurityException("Path is outside the workspace: " + relative);
        Path rel = root.relativize(p);
        if (!rel.toString().isEmpty() && (rel.getName(0).toString().equals(".git"))) {
            throw new SecurityException("The .git folder is off limits");
        }
        try {
            if (Files.exists(p) && !p.toRealPath().startsWith(root)) {
                throw new SecurityException("Path escapes the workspace through a link: " + relative);
            }
            Path parent = p.getParent();
            if (parent != null && Files.exists(parent) && !parent.toRealPath().startsWith(root)) {
                throw new SecurityException("Path escapes the workspace through a link: " + relative);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }

    private String rel(Path p) { return root.relativize(p).toString().replace('\\', '/'); }

    // ---------- read tools ----------

    public String listFiles(String dir) throws IOException {
        Path start = resolve(dir);
        if (!Files.isDirectory(start)) return "Not a directory: " + dir;
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(start, 6)) {
            walk.filter(Files::isRegularFile)
                    .map(this::rel)
                    .filter(r -> !r.startsWith(".git") && !r.contains("/.git/") && !r.contains("node_modules/")
                            && !r.startsWith("target/") && !r.startsWith("build/") && !r.startsWith("out/"))
                    .sorted().limit(MAX_LIST + 1).forEach(out::add);
        }
        String joined = String.join("\n", out.subList(0, Math.min(out.size(), MAX_LIST)));
        return out.size() > MAX_LIST ? joined + "\n...(more files; list a subfolder)" : joined;
    }

    public String readFile(String path) throws IOException {
        Path p = resolve(path);
        if (!Files.isRegularFile(p)) return "No such file: " + path;
        byte[] bytes = Files.readAllBytes(p);
        if (bytes.length > MAX_READ_BYTES) {
            return new String(bytes, 0, MAX_READ_BYTES, StandardCharsets.UTF_8) + "\n...(file truncated)";
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public String search(String regex) throws IOException {
        Pattern pattern;
        try {
            pattern = Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            return "Bad regex: " + e.getDescription();
        }
        List<String> hits = new ArrayList<>();
        for (String file : listFiles(".").split("\n")) {
            if (file.isBlank() || file.startsWith("...")) continue;
            Path p = root.resolve(file);
            if (Files.size(p) > MAX_READ_BYTES) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue; // binary or unreadable
            }
            for (int i = 0; i < lines.size() && hits.size() < 60; i++) {
                if (pattern.matcher(lines.get(i)).find()) hits.add(file + ":" + (i + 1) + ": " + lines.get(i).strip());
            }
        }
        return hits.isEmpty() ? "No matches" : String.join("\n", hits);
    }

    // ---------- write tools (rule 2: protected paths) ----------

    public String writeFile(String path, String content) throws IOException {
        Path p = checkWritable(path);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return "Wrote " + rel(p) + " (" + content.length() + " chars)";
    }

    /** Replaces one exact, unique snippet. Safer than rewriting whole files. */
    public String replaceInFile(String path, String oldText, String newText) throws IOException {
        Path p = checkWritable(path);
        if (!Files.isRegularFile(p)) return "No such file: " + path;
        String content = Files.readString(p, StandardCharsets.UTF_8);
        int first = content.indexOf(oldText);
        if (oldText.isEmpty() || first < 0) return "old_text not found in " + path + ". Read the file and copy the text exactly.";
        if (content.indexOf(oldText, first + 1) >= 0) return "old_text appears more than once in " + path + ". Include more surrounding lines.";
        Files.writeString(p, content.substring(0, first) + newText + content.substring(first + oldText.length()), StandardCharsets.UTF_8);
        return "Edited " + rel(p);
    }

    private Path checkWritable(String path) {
        Path p = resolve(path);
        if (project.isProtected(rel(p))) throw new SecurityException(rel(p) + " is protected and cannot be changed");
        return p;
    }

    // ---------- rule 3 and 4: named checks only, with timeout ----------

    public List<String> checkNames() { return List.copyOf(project.checks().keySet()); }

    // TODO: run each check in a container with no network (docker run --network none). Checks execute
    //  code the agent wrote; today the only isolation is the OS user botdesk runs as.
    public CheckResult runCheck(String name) {
        String command = project.checks().get(name);
        if (command == null) {
            return new CheckResult(name, -1, false, "Unknown check '" + name + "'. Available: " + checkNames());
        }
        try {
            Path log = Files.createTempFile("botdesk-check", ".log");
            Process p = new ProcessBuilder("bash", "-c", command).directory(root.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            boolean done = p.waitFor(checkTimeout.toSeconds(), TimeUnit.SECONDS);
            if (!done) p.descendants().forEach(ProcessHandle::destroyForcibly);
            if (!done) p.destroyForcibly();
            // Build tools colour their output; the escape codes are tokens with no meaning to a model.
            String output = tail(ANSI.matcher(Files.readString(log, StandardCharsets.UTF_8)).replaceAll(""));
            Files.deleteIfExists(log);
            return new CheckResult(name, done ? p.exitValue() : -1, !done, output);
        } catch (IOException e) {
            return new CheckResult(name, -1, false, "Could not run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CheckResult(name, -1, false, "Interrupted");
        }
    }

    public List<CheckResult> runAllChecks() {
        return checkNames().stream().map(this::runCheck).toList();
    }

    private static String tail(String s) {
        return s.length() <= MAX_OUTPUT_CHARS ? s : "...(earlier output cut)\n" + s.substring(s.length() - MAX_OUTPUT_CHARS);
    }
}
