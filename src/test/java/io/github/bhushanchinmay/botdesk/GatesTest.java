package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.quality.Gate;
import io.github.bhushanchinmay.botdesk.quality.GateInput;
import io.github.bhushanchinmay.botdesk.quality.QualityGates;

import java.util.List;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

public final class GatesTest {

    private static final Project P = Fixtures.gitProject(Fixtures.tempDir(), "");
    private static final Playbook BUGFIX = new Playbook("bugfix", "", "", "", "", true, false, false, false);
    private static final Playbook INVESTIGATE = new Playbook("investigate", "", "", "", "", false, true, true, false);
    private static final List<Workspace.CheckResult> GREEN = List.of(new Workspace.CheckResult("test", 0, false, "ok"));
    private static final QualityGates GATES = QualityGates.defaults();

    private static List<String> run(Playbook pb, List<String> nameStatus, String diff, List<Workspace.CheckResult> checks) {
        return GATES.check(GateInput.fromNameStatus(P, pb, nameStatus, diff, checks));
    }

    public static void testCleanFixPasses() {
        eq(List.of(), run(BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/add_test.txt"), "+a + b", GREEN), "passes");
    }

    public static void testEachRuleFires() {
        check(run(BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/t.txt"), "",
                List.of(new Workspace.CheckResult("test", 1, false, "boom"))).get(0).contains("failed"), "failing check");
        check(run(BUGFIX, List.of("M\tsrc/calc.txt"), "", GREEN).get(0).contains("No test"), "test required");
        check(run(BUGFIX, List.of(), "", GREEN).get(0).contains("No code"), "empty change");
        check(run(BUGFIX, List.of("M\tsrc/calc.txt", "D\ttests/existing_test.txt"), "", GREEN)
                .stream().anyMatch(s -> s.contains("deleted")), "deleting tests is caught");
        check(run(BUGFIX, List.of("M\ttest.sh", "A\ttests/t.txt"), "", GREEN)
                .stream().anyMatch(s -> s.contains("Protected")), "protected file caught");
        check(run(BUGFIX, List.of("M\tsrc/calc.txt", "A\ttests/t.txt"),
                "+key = \"sk-abcdefghijklmnopqrstuvwxyz123\"", GREEN).get(0).contains("secret"), "secret caught");
        check(run(BUGFIX, List.of("R100\tsrc/old.txt\tsrc/calc.txt", "A\ttests/t.txt"), "", GREEN).isEmpty(),
                "renames are read by their new path");
    }

    public static void testReadOnlyTasks() {
        eq(List.of(), run(INVESTIGATE, List.of(), "", GREEN), "report with no change passes");
        eq(List.of(), run(INVESTIGATE, List.of(), "", List.of(new Workspace.CheckResult("test", 1, false, "red"))),
                "a red build does not block a question");
        check(run(INVESTIGATE, List.of("M\tsrc/calc.txt"), "", GREEN).get(0).contains("read-only"),
                "any change fails a read-only task");
    }

    public static void testCustomGatePlugsIn() {
        Gate noTodo = new Gate() {
            public String name() { return "no-todo"; }
            public List<String> check(GateInput in) {
                return in.diff().contains("+TODO") ? List.of("Do not add TODOs") : List.of();
            }
        };
        QualityGates custom = GATES.with(noTodo);
        eq(List.of("Do not add TODOs"), custom.check(GateInput.fromNameStatus(P, BUGFIX,
                List.of("M\tsrc/calc.txt", "A\ttests/t.txt"), "+TODO later", GREEN)), "custom rule runs with the built-ins");
        check(custom.names().contains("no-todo") && custom.names().contains("secret-scan"), "names listed");
    }
}
