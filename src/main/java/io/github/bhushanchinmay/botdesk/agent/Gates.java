package io.github.bhushanchinmay.botdesk.agent;

import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Quality gates that run as plain code before the reviewer model sees anything.
 * The agent's own claim "tests pass" is never trusted: botdesk re-runs every check itself.
 * A model can ignore a prompt. It cannot ignore these.
 */
public final class Gates {

    private static final Pattern SECRET = Pattern.compile(
            "(sk-[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|ghp_[A-Za-z0-9]{30,}|xox[abp]-[A-Za-z0-9-]{10,}"
                    + "|-----BEGIN [A-Z ]*PRIVATE KEY-----)");

    private Gates() {}

    /**
     * @param nameStatus git diff --name-status lines, e.g. "M\tsrc/Cart.java"
     * @param diff       full diff of the change, for the secret scan
     * @param checks     results of re-running every project check
     */
    public static List<String> check(Project project, Playbook playbook, List<String> nameStatus, String diff,
                                     List<Workspace.CheckResult> checks) {
        List<String> problems = new ArrayList<>();

        // A read-only task changes nothing, so failing checks are reported on the task page, not blocking:
        // you must be able to ask questions about a broken build.
        for (Workspace.CheckResult c : checks) {
            if (!c.passed() && !playbook.readOnly()) {
                problems.add("Check '" + c.name() + "' " + (c.timedOut() ? "timed out" : "failed (exit " + c.exitCode() + ")")
                        + ":\n" + c.output());
            }
        }

        if (playbook.readOnly() && !nameStatus.isEmpty()) {
            problems.add("This is a read-only task but files were changed: " + nameStatus + ". Revert them and only report.");
        }
        if (nameStatus.isEmpty() && !playbook.allowEmptyDiff()) {
            problems.add("No code was changed. This kind of task must change code.");
        }

        boolean touchedTest = false;
        for (String line : nameStatus) {
            String[] parts = line.split("\t");
            String status = parts[0];
            String path = parts[parts.length - 1];
            if (project.isProtected(path)) problems.add("Protected file changed: " + path);
            if (project.isTestPath(path)) {
                if (status.startsWith("D")) problems.add("A test file was deleted: " + path + ". Do not weaken tests.");
                else touchedTest = true;
            }
        }
        if (playbook.requireTestChange() && !nameStatus.isEmpty() && !touchedTest) {
            problems.add("No test was added or changed. Add a test that proves the change works.");
        }

        for (String line : diff.split("\n")) {
            if (line.startsWith("+") && SECRET.matcher(line).find()) {
                problems.add("Possible secret in the change: " + line.substring(0, Math.min(line.length(), 60)) + "...");
            }
        }
        return problems;
    }
}
