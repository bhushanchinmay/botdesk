package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.orchestrator.PipelineServices;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * RELEASING -> DONE. Only the exact commit you approved goes out, as a draft PR.
 * A human merges; botdesk never writes to the base branch.
 */
public final class ReleaseStage implements Stage {

    private final StageSupport support;

    public ReleaseStage(StageSupport support) { this.support = support; }

    @Override
    public Set<TaskState> handles() { return Set.of(TaskState.RELEASING); }

    @Override
    public void run(Task task, Project project, Playbook playbook) {
        PipelineServices s = support.services();
        Path wt = support.worktree(task);
        String head = s.git().head(wt);
        if (!head.equals(task.approvedCommit())) {
            throw new IllegalStateException("Branch moved after approval (approved " + StageSupport.shortSha(task.approvedCommit())
                    + ", now " + StageSupport.shortSha(head) + "). Not releasing.");
        }
        if (head.equals(task.baseCommit())) {
            s.store().addEvent(task.id(), "release", "report", "No code change; report kept on the task page.");
        } else if (project.hasGithub() && s.github().configured()) {
            s.git().push(wt, project.remote(), task.branch());
            // TODO: look up an open PR for this branch before creating one. Today a retry after
            //  "PR created, then crash before saving its URL" asks GitHub for a second PR and fails.
            String url = s.github().openDraftPullRequest(project.githubRepo(), task.branch(), project.baseBranch(),
                    "[botdesk #" + task.id() + "] " + task.title(), prBody(task, s));
            task.setPrUrl(url);
            s.store().addEvent(task.id(), "release", "pr", url);
        } else {
            s.store().addEvent(task.id(), "release", "branch", "Branch " + task.branch() + " is ready in " + project.repo()
                    + ". Review and merge with: git merge " + task.branch());
        }
        s.tasks().move(task, TaskState.DONE, "release", null);
        s.git().removeWorktree(project.repo(), wt);
    }

    /** The PR carries its own evidence, so a reviewer does not need botdesk open to trust it. */
    @SuppressWarnings("unchecked")
    private static String prBody(Task task, PipelineServices s) {
        Map<String, Object> out = s.store().latestArtifact(task.id()).map(a -> Json.parseObject(a.content())).orElse(Map.of());
        StringBuilder checks = new StringBuilder();
        boolean allPassed = true;
        for (Object o : Obj.list(out, "checks")) {
            Map<String, Object> c = (Map<String, Object>) o;
            boolean passed = Obj.bool(c, "passed", false);
            allPassed &= passed;
            checks.append("\n  - ").append(Obj.str(c, "name", "?")).append(passed ? ": passed" : ": FAILED");
        }
        return "## Task\n" + task.goal() + "\n\n## Plan\n" + StageSupport.planOrDefault(task) + "\n\n## Acceptance\n" + StageSupport.acceptanceOrDefault(task)
                + "\n\n## Engineer summary\n" + Obj.str(out, "summary", "")
                + "\n\n## Evidence\n- Checks, re-run by botdesk (not self-reported):" + checks
                + (allPassed ? "" : "\n\n> **Warning:** some checks fail. This was accepted by a human after the repair limit.")
                + "\n- Reviewer score: " + (task.reviewScore() < 0 ? "not reviewed" : task.reviewScore() + "/10")
                + "\n- Approved commit: " + task.approvedCommit()
                + "\n- Repair cycles: " + task.repairCycles();
    }
}
