package io.github.bhushanchinmay.botdesk.quality;

import io.github.bhushanchinmay.botdesk.agent.Workspace.CheckResult;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The rules botdesk ships with, one small class each. Every rule is plain code: no model is asked,
 * so the verdict costs no tokens and cannot be argued with.
 */
final class BuiltInGates {

    private BuiltInGates() {}

    /**
     * Every project check (build, test, lint) must pass.
     * Read-only tasks are exempt: you must be able to ask questions about a broken build.
     */
    static final class ChecksPassGate implements Gate {

        @Override
        public String name() { return "checks-pass"; }

        @Override
        public List<String> check(GateInput in) {
            if (in.playbook().readOnly()) return List.of();
            return in.checks().stream().filter(c -> !c.passed()).map(ChecksPassGate::describe).toList();
        }

        private static String describe(CheckResult c) {
            String verdict = c.timedOut() ? "timed out" : "failed (exit " + c.exitCode() + ")";
            return "Check '" + c.name() + "' " + verdict + ":\n" + c.output();
        }
    }

    /** Support questions (investigate) must not change any file. */
    static final class ReadOnlyGate implements Gate {

        @Override
        public String name() { return "read-only"; }

        @Override
        public List<String> check(GateInput in) {
            if (!in.playbook().readOnly() || in.changes().isEmpty()) return List.of();
            return List.of("This is a read-only task but files were changed: " + in.changes() + ". Revert them and only report.");
        }
    }

    /** Code-changing kinds of work must actually change code. */
    static final class ChangeRequiredGate implements Gate {

        @Override
        public String name() { return "change-required"; }

        @Override
        public List<String> check(GateInput in) {
            if (!in.changes().isEmpty() || in.playbook().allowEmptyDiff()) return List.of();
            return List.of("No code was changed. This kind of task must change code.");
        }
    }

    /**
     * Protected files (test runners, CI config) must not change.
     * The Workspace already refuses writes to them; this gate is the second line of defence,
     * for example if a check script itself rewrote a protected file.
     */
    static final class ProtectedPathGate implements Gate {

        @Override
        public String name() { return "protected-paths"; }

        @Override
        public List<String> check(GateInput in) {
            return in.changes().stream()
                    .filter(c -> in.project().isProtected(c.path()))
                    .map(c -> "Protected file changed: " + c.path())
                    .toList();
        }
    }

    /** Never delete tests, and prove behaviour changes with a test when the playbook requires it. */
    static final class TestDisciplineGate implements Gate {

        @Override
        public String name() { return "test-discipline"; }

        @Override
        public List<String> check(GateInput in) {
            List<String> problems = new ArrayList<>();
            boolean touchedTest = false;
            for (FileChange change : in.changes()) {
                if (!in.project().isTestPath(change.path())) continue;
                if (change.isDeleted()) problems.add("A test file was deleted: " + change.path() + ". Do not weaken tests.");
                else touchedTest = true;
            }
            if (in.playbook().requireTestChange() && !in.changes().isEmpty() && !touchedTest) {
                problems.add("No test was added or changed. Add a test that proves the change works.");
            }
            return problems;
        }
    }

    /**
     * Added lines must not contain anything that looks like a credential.
     * Regex-based, so it catches common key formats only. A dedicated scanner such as gitleaks
     * would be the upgrade; it would plug in as another Gate.
     */
    static final class SecretScanGate implements Gate {

        private static final Pattern SECRET = Pattern.compile(
                "(sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|xox[abp]-[A-Za-z0-9-]{10,}"
                        + "|-----BEGIN [A-Z ]*PRIVATE KEY-----)");
        private static final int PREVIEW_CHARS = 60;

        @Override
        public String name() { return "secret-scan"; }

        @Override
        public List<String> check(GateInput in) {
            return Arrays.stream(in.diff().split("\n"))
                    .filter(line -> line.startsWith("+") && SECRET.matcher(line).find())
                    .map(line -> "Possible secret in the change: " + line.substring(0, Math.min(line.length(), PREVIEW_CHARS)) + "...")
                    .toList();
        }
    }
}
