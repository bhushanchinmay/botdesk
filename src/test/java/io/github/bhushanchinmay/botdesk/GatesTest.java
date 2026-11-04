package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.Gates;
import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.List;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class GatesTest {

    private static final Project P = Fixtures.gitProject(Fixtures.tempDir(), "");
    private static final Playbook BUGFIX = new Playbook("bugfix", "", "", "", "", true, false, false);
    private static final Playbook INVESTIGATE = new Playbook("investigate", "", "", "", "", false, true, true);
    private static final List<Workspace.CheckResult> GREEN = List.of(new Workspace.CheckResult("test", 0, false, "ok"));

    public static void testCleanFixPasses() {
        eq(List.of(), Gates.check(P, BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/add_test.txt"), "+a + b", GREEN), "passes");
    }

    public static void testEachRuleFires() {
        check(Gates.check(P, BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/t.txt"), "",
                List.of(new Workspace.CheckResult("test", 1, false, "boom"))).get(0).contains("failed"), "failing check");
        check(Gates.check(P, BUGFIX, List.of("M\tsrc/calc.txt"), "", GREEN).get(0).contains("No test"), "test required");
        check(Gates.check(P, BUGFIX, List.of(), "", GREEN).get(0).contains("No code"), "empty change");
        check(Gates.check(P, BUGFIX, List.of("M\tsrc/calc.txt", "D\ttests/existing_test.txt"), "", GREEN)
                .stream().anyMatch(s -> s.contains("deleted")), "deleting tests is caught");
        check(Gates.check(P, BUGFIX, List.of("M\ttest.sh", "A\ttests/t.txt"), "", GREEN)
                .stream().anyMatch(s -> s.contains("Protected")), "protected file caught");
        check(Gates.check(P, BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/t.txt"),
                "+key = \"sk-abcdefghijklmnopqrstuvwxyz123\"", GREEN).get(0).contains("secret"), "secret caught");
    }

    public static void testReadOnlyTasks() {
        eq(List.of(), Gates.check(P, INVESTIGATE, List.of(), "", GREEN), "report with no change passes");
        eq(List.of(), Gates.check(P, INVESTIGATE, List.of(), "",
                List.of(new Workspace.CheckResult("test", 1, false, "red"))), "a red build does not block a question");
        check(Gates.check(P, INVESTIGATE, List.of("M\tsrc/calc.txt"), "", GREEN).get(0).contains("read-only"),
                "any change fails a read-only task");
    }
}
