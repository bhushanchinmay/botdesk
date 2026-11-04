package io.github.bhushanchinmay.botdesk.project;

import io.github.bhushanchinmay.botdesk.config.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Thin wrapper over the git command line. Each task gets its own worktree: a separate folder
 * on its own branch that shares the repo's history. Tasks never touch the main checkout or
 * each other, and nothing here ever writes to the base branch.
 */
public final class Git {

    private static final Duration TIMEOUT = Duration.ofSeconds(120);

    private final Config.GitIdentity identity;

    public Git(Config.GitIdentity identity) { this.identity = identity; }

    /** Creates branch + worktree from the base branch. Returns the base commit SHA. */
    public String addWorktree(Path repo, Path worktree, String branch, String baseBranch) {
        String base = run(repo, "rev-parse", baseBranch).trim();
        run(repo, "worktree", "add", "-b", branch, worktree.toString(), base);
        return base;
    }

    public void removeWorktree(Path repo, Path worktree) {
        if (Files.exists(worktree)) runAllowFail(repo, "worktree", "remove", "--force", worktree.toString());
        runAllowFail(repo, "worktree", "prune");
    }

    /** Commits every change in the worktree. Returns the new HEAD SHA, or the old HEAD if nothing changed. */
    public String commitAll(Path worktree, String message) {
        run(worktree, "add", "-A");
        boolean nothingStaged = runAllowFail(worktree, "diff", "--cached", "--quiet").exitCode == 0;
        if (!nothingStaged) run(worktree, "commit", "-q", "-m", message);
        return head(worktree);
    }

    public String head(Path worktree) { return run(worktree, "rev-parse", "HEAD").trim(); }

    public String diff(Path worktree, String base) { return run(worktree, "diff", base + "..HEAD"); }

    /** Lines like "M\tsrc/Cart.java" or "D\ttests/CartTest.java". */
    public List<String> nameStatus(Path worktree, String base) {
        String out = run(worktree, "diff", "--name-status", base + "..HEAD").trim();
        return out.isEmpty() ? List.of() : Arrays.asList(out.split("\n"));
    }

    public void push(Path worktree, String remote, String branch) {
        run(worktree, "push", "-u", remote, branch);
    }

    // ---------- process plumbing ----------

    private record Outcome(int exitCode, String output) {}

    private String run(Path dir, String... args) {
        Outcome o = exec(dir, args);
        if (o.exitCode != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + o.output.trim());
        }
        return o.output;
    }

    private Outcome runAllowFail(Path dir, String... args) { return exec(dir, args); }

    private Outcome exec(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("GIT_AUTHOR_NAME", identity.name());
        env.put("GIT_AUTHOR_EMAIL", identity.email());
        env.put("GIT_COMMITTER_NAME", identity.name());
        env.put("GIT_COMMITTER_EMAIL", identity.email());
        env.put("GIT_TERMINAL_PROMPT", "0");
        // Send output to a file, not a pipe: reading a pipe first would block until git exits,
        // so the timeout below could never fire (for example a push stuck on the network).
        Path log = null;
        try {
            log = Files.createTempFile("botdesk-git", ".log");
            pb.redirectOutput(log.toFile());
            Process p = pb.start();
            if (!p.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Outcome(-1, "git timed out after " + TIMEOUT.toSeconds() + "s");
            }
            return new Outcome(p.exitValue(), Files.readString(log, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Outcome(-1, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(-1, "interrupted");
        } finally {
            if (log != null) {
                try {
                    Files.deleteIfExists(log);
                } catch (IOException ignored) {
                    // temp file; the OS cleans it up
                }
            }
        }
    }
}
