package io.github.bhushanchinmay.botdesk.quality;

import io.github.bhushanchinmay.botdesk.agent.Workspace.CheckResult;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.List;

/**
 * Everything a gate may look at. Checks are the results botdesk got by re-running them itself,
 * never the engineer's own claim.
 */
public record GateInput(Project project, Playbook playbook, List<FileChange> changes, String diff,
                        List<CheckResult> checks) {

    public GateInput {
        changes = List.copyOf(changes);
        checks = List.copyOf(checks);
    }

    public static GateInput fromNameStatus(Project project, Playbook playbook, List<String> nameStatus,
                                           String diff, List<CheckResult> checks) {
        return new GateInput(project, playbook, nameStatus.stream().map(FileChange::parse).toList(), diff, checks);
    }
}
