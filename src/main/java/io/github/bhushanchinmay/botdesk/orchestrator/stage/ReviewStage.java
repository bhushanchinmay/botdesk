package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.domain.Records.Artifact;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.orchestrator.PipelineServices;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * IN_REVIEW -> NEEDS_YOU or REVISING. A second model, ideally from a different family,
 * judges what tests cannot: correctness against the plan, edge cases, scope, test quality.
 */
public final class ReviewStage implements Stage {

    private static final String SYSTEM = """
            You are the code reviewer on a small software team. You never write the code yourself.
            All checks already passed. Judge what tests cannot: correctness against the acceptance criteria,
            edge cases, readability, scope creep, and whether the tests really prove the change.
            Score 1-10. 8 or more means you would approve this pull request at a good company.
            Return only JSON: {"score": 1-10, "issues": ["specific issue and how to fix it"], "summary": "2-3 lines for the human lead"}
            """;

    private final StageSupport support;

    public ReviewStage(StageSupport support) { this.support = support; }

    @Override
    public Set<TaskState> handles() { return Set.of(TaskState.IN_REVIEW); }

    @Override
    public void run(Task task, Project project, Playbook playbook) {
        PipelineServices s = support.services();
        Artifact artifact = s.store().latestArtifact(task.id())
                .orElseThrow(() -> new IllegalStateException("No output to review for task " + task.id()));
        Map<String, Object> out = Json.parseObject(artifact.content());

        // Token saver: a model's verdict on identical code will not change, so do not ask again.
        if (task.headCommit().equals(task.reviewedCommit())) {
            support.sendBack(task, "No code changed since the last review of commit " + StageSupport.shortSha(task.headCommit())
                    + ". Change the code to address the review, or call finish and explain why you cannot.\nEarlier review:\n"
                    + task.lastFeedback());
            return;
        }

        String user = "Task: " + task.title() + "\n" + task.goal()
                + "\n\nPlan:\n" + StageSupport.planOrDefault(task) + "\n\nAcceptance criteria:\n" + StageSupport.acceptanceOrDefault(task)
                + "\n\nEngineer's summary:\n" + Obj.str(out, "summary", "")
                + "\n\nChanged files:\n" + String.join("\n", Obj.strings(out, "changes"))
                + "\n\nDiff:\n" + StageSupport.clip(Obj.str(out, "diff", ""), StageSupport.MAX_PROMPT_CHARS);
        Map<String, Object> json = support.askJson(task, "reviewer",
                SYSTEM + "\nRubric for this kind of work:\n" + playbook.reviewRubric(), user);

        int score = (int) Math.max(0, Math.min(10, Obj.num(json, "score", 0)));
        task.recordReview(score, task.headCommit());
        s.store().save(task);
        List<String> issues = Obj.strings(json, "issues");
        s.store().addEvent(task.id(), "reviewer", "score", score + "/10" + (issues.isEmpty() ? "" : ": " + String.join("; ", issues)));

        int pass = s.config().reviewPassScore();
        if (score < pass) {
            support.sendBack(task, "Reviewer scored " + score + "/10 (needs " + pass + "):\n- " + String.join("\n- ", issues));
            return;
        }
        s.tasks().move(task, TaskState.NEEDS_YOU, "reviewer", "scored " + score + "/10");
        s.tasks().raise(task, RequestKind.FINAL_APPROVAL, "Reviewer: " + score + "/10\n" + Obj.str(json, "summary", "")
                + "\n\nCommit " + StageSupport.shortSha(task.headCommit()) + ", changed files:\n"
                + String.join("\n", Obj.strings(out, "changes")) + "\n\n" + nextStep(task, project, s));
    }

    private static String nextStep(Task task, Project project, PipelineServices s) {
        if (task.headCommit().equals(task.baseCommit())) return "Approve to close it (report only, no code change).";
        if (project.hasGithub() && s.github().configured()) return "Approve to push " + task.branch() + " and open a draft PR.";
        return "Approve to keep branch " + task.branch() + " ready to merge in " + project.repo() + ".";
    }
}
