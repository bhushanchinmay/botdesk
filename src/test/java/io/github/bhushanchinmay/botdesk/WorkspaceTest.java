package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class WorkspaceTest {

    private static Workspace ws(Path dir, Duration timeout) {
        Project p = Fixtures.gitProject(dir, "");
        return new Workspace(p.repo(), p, timeout);
    }

    public static void testPathsCannotEscape() throws Exception {
        Path dir = Fixtures.tempDir();
        Workspace ws = ws(dir, Duration.ofSeconds(5));
        Files.writeString(dir.resolve("secret.txt"), "top secret");
        Files.createSymbolicLink(ws.root().resolve("link"), dir);
        for (String bad : List.of("../secret.txt", "/etc/passwd", ".git/config", "link/secret.txt")) {
            try {
                ws.readFile(bad);
                throw new AssertionError("read of " + bad + " should be refused");
            } catch (SecurityException expected) {
                // good
            }
        }
        check(ws.readFile("src/calc.txt").contains("a - b"), "normal read works");
    }

    public static void testProtectedFilesAreReadOnly() throws Exception {
        Workspace ws = ws(Fixtures.tempDir(), Duration.ofSeconds(5));
        check(ws.readFile("test.sh").contains("grep"), "protected file can be read");
        try {
            ws.writeFile("test.sh", "exit 0");
            throw new AssertionError("writing the test runner should be refused");
        } catch (SecurityException expected) {
            check(Files.readString(ws.root().resolve("test.sh")).contains("grep"), "file unchanged");
        }
    }

    public static void testReplaceNeedsUniqueMatch() throws Exception {
        Workspace ws = ws(Fixtures.tempDir(), Duration.ofSeconds(5));
        ws.writeFile("src/dup.txt", "x\nx\n");
        check(ws.replaceInFile("src/dup.txt", "x", "y").contains("more than once"), "ambiguous edit refused");
        check(ws.replaceInFile("src/calc.txt", "nope", "y").contains("not found"), "missing text reported");
        eq("Edited src/calc.txt", ws.replaceInFile("src/calc.txt", "a - b", "a + b"), "unique edit applied");
    }

    public static void testOnlyNamedChecksRun() throws Exception {
        Workspace ws = ws(Fixtures.tempDir(), Duration.ofSeconds(5));
        Workspace.CheckResult unknown = ws.runCheck("rm -rf /");
        check(!unknown.passed() && unknown.output().contains("Unknown check"), "free-form commands refused");
        check(!ws.runCheck("test").passed(), "buggy code fails the test check");
        ws.replaceInFile("src/calc.txt", "a - b", "a + b");
        ws.writeFile("tests/add_test.txt", "adds");
        check(ws.runCheck("test").passed(), "fixed code passes");
    }

    public static void testChecksTimeOut() {
        Path dir = Fixtures.tempDir();
        Project base = Fixtures.gitProject(dir, "");
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("slow", "sleep 10");
        Project p = new Project("slow", base.repo(), "main", checks, List.of(), List.of(), List.of(), "", "origin");
        long start = System.currentTimeMillis();
        Workspace.CheckResult r = new Workspace(p.repo(), p, Duration.ofSeconds(1)).runCheck("slow");
        check(r.timedOut() && !r.passed(), "timed out");
        check(System.currentTimeMillis() - start < 5_000, "did not wait for the full command");
    }
}
