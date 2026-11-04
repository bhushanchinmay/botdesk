package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.agent.CodingEngine;
import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.orchestrator.PipelineServices;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.quality.GateInput;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * QUEUED or REVISING -> IN_REVIEW or REVISING. The engineer works in the task's own git worktree,
 * botdesk commits the result, re-runs every check itself, and the quality gates decide.
 *
 * <p>Token saver: for playbooks marked checksFirst (maintenance), the checks run before any model.
 * If they all pass there is nothing to fix, and the task goes to you without a single model call.
 * If they fail, the failures go into the engineer's first prompt, saving it a round trip.
 */
public final class BuildStage implements Stage {

    private static final int MAX_DIFF_CHARS = 60_000;
    private static final int MAX_CHECK_OUTPUT_CHARS = 2_000;
    private static final String RULES = """
            You are a software engineer on a small team. You work only through the tools you are given.
            Rules:
            - Read before you edit. Keep the change small and focused on the task.
            - Follow the existing code style. Do not reformat unrelated code.
            - Prove your work: add or update a test when the task changes behavior.
            - Never weaken, skip or delete tests to make checks pass.
            - Run the checks before you finish. If you are blocked, call finish and say why.
            """;

    private final StageSupport support;

    public BuildStage(StageSupport support) { this.support = support; }

    @Override
    public Set<TaskState> handles() { return Set.of(TaskState.QUEUED, TaskState.REVISING); }

    @Override
    public void run(Task task, Project project, Playbook playbook) {
        PipelineServices s = support.services();
        if (task.state() == TaskState.QUEUED) {
            prepareWorkspace(task, project);
            s.tasks().move(task, TaskState.RUNNING, "system", "engineer started on " + task.branch());
        }
        Path wt = support.worktree(task);
        Workspace ws = new Workspace(wt, project, Duration.ofSeconds(s.config().checkTimeoutSeconds()));

        List<Workspace.CheckResult> baseline = List.of();
        if (playbook.checksFirst() && task.outputVersion() == 0) {
            baseline = ws.runAllChecks();
            if (baseline.stream().allMatch(Workspace.CheckResult::passed)) {
                reportHealthyWithoutModel(task, baseline);
                return;
            }
        }

        CodingEngine.Outcome outcome = s.engine().run(new CodingEngine.Job(task.id(), "engineer",
                instructions(project, playbook), assignment(task, baseline), ws, s.config().maxAgentSteps()));
        s.store().addEvent(task.id(), "engineer", "finished",
                outcome.steps() + " steps: " + StageSupport.firstLine(outcome.summary()));

        String head = s.git().commitAll(wt, "botdesk #" + task.id() + ": " + task.title()
                + " (v" + (task.outputVersion() + 1) + ")\n\n" + outcome.summary());
        task.newOutput(head);
        s.store().save(task);

        // Never trust "tests pass" from the agent: re-run every check here.
        List<Workspace.CheckResult> checks = ws.runAllChecks();
        List<String> changes = s.git().nameStatus(wt, task.baseCommit());
        String diff = s.git().diff(wt, task.baseCommit());
        List<String> problems = new ArrayList<>(s.gates().check(GateInput.fromNameStatus(project, playbook, changes, diff, checks)));
        if (!outcome.finished()) problems.add("The engineer hit the step limit before finishing.");

        s.store().addArtifact(task.id(), task.outputVersion(), Json.write(artifact(head, outcome.summary(), changes, checks, diff)));
        s.store().addEvent(task.id(), "gates", problems.isEmpty() ? "pass" : "fail",
                checksLine(checks) + (problems.isEmpty() ? "" : " | " + problems.size() + " problem(s)"));

        if (problems.isEmpty()) s.tasks().move(task, TaskState.IN_REVIEW, "gates", "all gates passed");
        else support.sendBack(task, "Quality gates failed:\n- " + String.join("\n- ", problems));
    }

    private void reportHealthyWithoutModel(Task task, List<Workspace.CheckResult> checks) {
        PipelineServices s = support.services();
        task.newOutput(task.baseCommit());
        s.store().save(task);
        String summary = "All checks passed before any change, so there was nothing to fix. No model was called.";
        s.store().addArtifact(task.id(), task.outputVersion(), Json.write(artifact(task.baseCommit(), summary, List.of(), checks, "")));
        s.store().addEvent(task.id(), "gates", "fast-path", checksLine(checks) + " | no model call");
        s.tasks().move(task, TaskState.NEEDS_YOU, "gates", "healthy, nothing to do");
        s.tasks().raise(task, RequestKind.FINAL_APPROVAL, "Build is healthy: " + checksLine(checks)
                + ".\nNo model was called, so this cost nothing.\n\nApprove to close it.");
    }

    /** Idempotent: a retry after a crash keeps the branch and the earlier work. */
    private void prepareWorkspace(Task task, Project project) {
        PipelineServices s = support.services();
        if (task.branch() != null && Files.isDirectory(support.worktree(task))) return;
        try {
            Files.createDirectories(s.config().workspacesDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String branch = "botdesk/task-" + task.id();
        String base = s.git().addWorktree(project.repo(), support.worktree(task), branch, project.baseBranch());
        task.startedWork(branch, base);
        s.store().save(task);
    }

    private String instructions(Project project, Playbook playbook) {
        return RULES
                + "\nChecks you can run: " + project.checks().keySet()
                + "\nProtected (read-only) paths: " + project.protectedPaths()
                + "\nTest locations: " + project.testPaths()
                + "\n\nGuide for this kind of work (" + playbook.kind() + "):\n" + playbook.engineerGuide()
                + "\n\nProject context:\n" + support.projectContext(project);
    }

    private static String assignment(Task task, List<Workspace.CheckResult> baseline) {
        StringBuilder sb = new StringBuilder()
                .append("Task #").append(task.id()).append(": ").append(task.title()).append("\n").append(task.goal())
                .append("\n\nPlan approved by your lead:\n").append(StageSupport.planOrDefault(task))
                .append("\n\nAcceptance criteria:\n").append(StageSupport.acceptanceOrDefault(task));
        for (Workspace.CheckResult c : baseline) {
            if (!c.passed()) {
                sb.append("\n\nCheck '").append(c.name()).append("' already fails before your change (no need to run it first):\n")
                        .append(StageSupport.clip(c.output(), MAX_CHECK_OUTPUT_CHARS));
            }
        }
        if (task.state() == TaskState.REVISING) {
            sb.append("\n\nYour previous attempt is already in the workspace (commit ")
                    .append(StageSupport.shortSha(task.headCommit())).append("). It was sent back. Fix this:\n")
                    .append(task.lastFeedback());
        }
        return sb.toString();
    }

    private static Map<String, Object> artifact(String commit, String summary, List<String> changes,
                                                List<Workspace.CheckResult> checks, String diff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commit", commit);
        m.put("summary", summary);
        m.put("changes", changes);
        m.put("checks", checks.stream().map(c -> Map.of("name", c.name(), "passed", c.passed(),
                "exitCode", c.exitCode(), "output", StageSupport.clip(c.output(), MAX_CHECK_OUTPUT_CHARS))).toList());
        m.put("diff", StageSupport.clip(diff, MAX_DIFF_CHARS));
        return m;
    }

    private static String checksLine(List<Workspace.CheckResult> checks) {
        return checks.stream().map(c -> c.name() + (c.passed() ? " ok" : " FAIL"))
                .reduce((a, b) -> a + ", " + b).orElse("no checks");
    }
}
