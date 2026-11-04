package io.github.bhushanchinmay.botdesk.project;

import io.github.bhushanchinmay.botdesk.json.Obj;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An attached codebase: one JSON file in projects/. This is how the team works on "any project":
 * it only needs to know where the repo is and which commands prove the code works.
 *
 * checks: named shell commands, run in order (for example build, then test). These are the ONLY
 * commands the agents may run. protectedPaths: files agents may not change (CI config, test runner),
 * so they cannot make a check pass by weakening it.
 */
public record Project(
        String name,
        Path repo,
        String baseBranch,
        Map<String, String> checks,
        List<String> context,
        List<String> testPaths,
        List<String> protectedPaths,
        String githubRepo,
        String remote) {

    private static final Set<String> KEYS = Set.of("name", "repo", "baseBranch", "checks", "context",
            "testPaths", "protectedPaths", "githubRepo", "remote");

    public static Project fromMap(Map<String, Object> m, Path baseDir) {
        Obj.requireKnownKeys(m, "project", KEYS);
        Map<String, String> checks = new LinkedHashMap<>();
        Obj.map(m, "checks").forEach((k, v) -> checks.put(k, String.valueOf(v)));
        return new Project(
                Obj.str(m, "name", ""),
                baseDir.resolve(Obj.str(m, "repo", "")).normalize(),
                Obj.str(m, "baseBranch", "main"),
                checks,
                Obj.strings(m, "context"),
                Obj.strings(m, "testPaths"),
                Obj.strings(m, "protectedPaths"),
                Obj.str(m, "githubRepo", ""),
                Obj.str(m, "remote", "origin"));
    }

    public boolean isTestPath(String path) {
        return testPaths.stream().anyMatch(path::startsWith)
                || path.matches("(?i).*(test|spec)[^/]*\\.[a-z]+$");
    }

    public boolean isProtected(String path) {
        return protectedPaths.stream().anyMatch(p -> path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/"));
    }

    public boolean hasGithub() { return githubRepo != null && !githubRepo.isBlank(); }
}
