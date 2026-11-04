package io.github.bhushanchinmay.botdesk.orchestrator;

import io.github.bhushanchinmay.botdesk.agent.CodingEngine;
import io.github.bhushanchinmay.botdesk.agent.Gates;
import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.domain.Records.Artifact;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The head chef of the software kitchen. It never writes code itself. Each tick it starts due
 * routines, picks the oldest task the system (not you) can move forward, and runs one step:
 *
 *   BACKLOG    -> lead plans          -> CONFIRMING (you approve the plan)
 *   QUEUED     -> worktree + engineer -> quality gates -> IN_REVIEW, or REVISING with the failures
 *   IN_REVIEW  -> reviewer scores 1-10 -> NEEDS_YOU (score >= pass) or REVISING
 *   RELEASING  -> verify approved commit, push, draft PR -> DONE
 *
 * Each rejection is one repair cycle; past the limit, you decide.
 */
public final class Orchestrator {

    private static final Set<TaskState> ACTIONABLE = EnumSet.of(
            TaskState.BACKLOG, TaskState.QUEUED, TaskState.REVISING, TaskState.IN_REVIEW, TaskState.RELEASING);
    private static final int MAX_PROMPT_CHARS = 24_000;

    private final Config config;
    private final Store store;
    private final Registry registry;
    private final LlmClient llm;
    private final TaskService tasks;
    private final CodingEngine engine;
    private final Git git;
    private final GitHub github;

    public Orchestrator(Config config, Store store, Registry registry, LlmClient llm, TaskService tasks,
                        CodingEngine engine, Git git, GitHub github) {
        this.config = config;
        this.store = store;
        this.registry = registry;
        this.llm = llm;
        this.tasks = tasks;
        this.engine = engine;
        this.git = git;
        this.github = github;
    }

    /** Runs due routines, then up to maxSteps task steps. Returns how many steps ran. */
    public int tick(int maxSteps) {
        startDueRoutines(Instant.now());
        int steps = 0;
        while (steps < maxSteps) {
            Optional<Task> next = store.tasks().stream()
                    .filter(t -> ACTIONABLE.contains(t.state()))
                    .min(Comparator.comparing(Task::updatedAt));
            if (next.isEmpty()) break;
            step(next.get());
            steps++;
        }
        cleanUpFinishedWorkspaces();
        return steps;
    }

    void startDueRoutines(Instant now) {
        for (Config.Routine routine : config.routines()) {
            if (!routine.enabled()) continue;
            Optional<Instant> last = store.lastRoutineRun(routine.name());
            if (last.isEmpty() || Duration.between(last.get(), now).toHours() >= routine.everyHours()) {
                store.markRoutineRun(routine.name(), now);
                tasks.createPreConfirmed(routine.project(), routine.kind(), routine.title(), routine.goal());
            }
        }
    }

    void step(Task task) {
        try {
            Project project = registry.project(task.project())
                    .orElseThrow(() -> new IllegalStateException("Project '" + task.project() + "' is not attached"));
            Playbook playbook = registry.playbook(task.kind())
                    .orElseThrow(() -> new IllegalStateException("No playbook for kind '" + task.kind() + "'"));
            switch (task.state()) {
                case BACKLOG -> plan(task, project, playbook);
                case QUEUED -> {
                    prepareWorkspace(task, project);
                    tasks.move(task, TaskState.RUNNING, "system", "engineer started on " + task.branch());
                    build(task, project, playbook);
                }
                case REVISING -> build(task, project, playbook);
                case IN_REVIEW -> review(task, project, playbook);
                case RELEASING -> release(task, project);
                default -> { /* waiting for you */ }
            }
        } catch (LlmClient.BudgetExceededException e) {
            fail(task, RequestKind.BUDGET, e.getMessage());
        } catch (RuntimeException e) {
            fail(task, RequestKind.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---------- 1. tech lead: plan before any code ----------

    private void plan(Task task, Project project, Playbook playbook) {
        String system = """
                You are the tech lead of a small software team. You never write code.
                Turn the request into a short plan the engineer can follow, and acceptance criteria
                that can be checked by running the project's checks or by reading the diff.
                Keep the change as small as possible. Ask questions only if something is genuinely unclear.
                Return only JSON: {"restatement": "...", "plan": ["step", "..."], "acceptance": ["...", "..."], "questions": ["..."]}
                """ + "\nGuide for this kind of work (" + playbook.kind() + "):\n" + playbook.leadGuide();
        String user = "Project: " + project.name() + "\nChecks: " + project.checks().keySet()
                + "\n\nTask: " + task.title() + "\n" + task.goal()
                + "\n\nRepository files:\n" + clip(listRepo(project), 6_000)
                + "\n\nProject context:\n" + projectContext(project);
        Map<String, Object> json = askJson(task, "lead", system, user);

        String plan = numbered(Obj.strings(json, "plan"));
        String acceptance = bullets(Obj.strings(json, "acceptance"));
        task.setPlan(plan, acceptance);
        store.save(task);
        List<String> questions = Obj.strings(json, "questions");
        tasks.move(task, TaskState.CONFIRMING, "lead", "planned");
        tasks.raise(task, RequestKind.CONFIRM_TASK, "Lead's understanding:\n" + Obj.str(json, "restatement", "")
                + "\n\nPlan:\n" + plan + "\n\nDone means:\n" + acceptance
                + (questions.isEmpty() ? "" : "\n\nQuestions:\n" + bullets(questions))
                + "\n\nApprove to start, reply to change the plan, or reject to cancel.");
    }

    // ---------- 2. engineer: works in its own worktree, then code gates check the result ----------

    private void prepareWorkspace(Task task, Project project) {
        if (task.branch() != null && Files.isDirectory(worktree(task))) return; // retry: keep earlier work
        try {
            Files.createDirectories(config.workspacesDir());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String branch = "botdesk/task-" + task.id();
        String base = git.addWorktree(project.repo(), worktree(task), branch, project.baseBranch());
        task.startedWork(branch, base);
        store.save(task);
    }

    private void build(Task task, Project project, Playbook playbook) {
        Path wt = worktree(task);
        Workspace ws = new Workspace(wt, project, Duration.ofSeconds(config.checkTimeoutSeconds()));

        String instructions = """
                You are a software engineer on a small team. You work only through the tools you are given.
                Rules:
                - Read before you edit. Keep the change small and focused on the task.
                - Follow the existing code style. Do not reformat unrelated code.
                - Prove your work: add or update a test when the task changes behavior.
                - Never weaken, skip or delete tests to make checks pass.
                - Run the checks before you finish. If you are blocked, call finish and say why.
                """
                + "\nChecks you can run: " + project.checks().keySet()
                + "\nProtected (read-only) paths: " + project.protectedPaths()
                + "\nTest locations: " + project.testPaths()
                + "\n\nGuide for this kind of work (" + playbook.kind() + "):\n" + playbook.engineerGuide()
                + "\n\nProject context:\n" + projectContext(project);
        StringBuilder assignment = new StringBuilder()
                .append("Task #").append(task.id()).append(": ").append(task.title()).append("\n").append(task.goal())
                .append("\n\nPlan approved by your lead:\n").append(task.plan())
                .append("\n\nAcceptance criteria:\n").append(task.acceptance());
        if (task.state() == TaskState.REVISING) {
            assignment.append("\n\nYour previous attempt is already in the workspace (commit ")
                    .append(TaskService.shortSha(task.headCommit())).append("). It was sent back. Fix this:\n")
                    .append(task.lastFeedback());
        }

        CodingEngine.Outcome outcome = engine.run(new CodingEngine.Job(task.id(), "engineer", instructions,
                assignment.toString(), ws, config.maxAgentSteps()));
        store.addEvent(task.id(), "engineer", "finished", outcome.steps() + " steps: " + firstLine(outcome.summary()));

        String head = git.commitAll(wt, "botdesk #" + task.id() + ": " + task.title() + " (v" + (task.outputVersion() + 1) + ")"
                + "\n\n" + outcome.summary());
        task.newOutput(head);
        store.save(task);

        // Never trust "tests pass" from the agent: re-run every check here.
        List<Workspace.CheckResult> checks = ws.runAllChecks();
        List<String> changes = git.nameStatus(wt, task.baseCommit());
        String diff = git.diff(wt, task.baseCommit());
        List<String> problems = new java.util.ArrayList<>(Gates.check(project, playbook, changes, diff, checks));
        if (!outcome.finished()) problems.add("The engineer hit the step limit before finishing.");

        store.addArtifact(task.id(), task.outputVersion(), Json.write(artifact(head, outcome.summary(), changes, checks, diff)));
        store.addEvent(task.id(), "gates", problems.isEmpty() ? "pass" : "fail",
                checksLine(checks) + (problems.isEmpty() ? "" : " | " + problems.size() + " problem(s)"));

        if (problems.isEmpty()) tasks.move(task, TaskState.IN_REVIEW, "gates", "all gates passed");
        else sendBack(task, "Quality gates failed:\n- " + String.join("\n- ", problems));
    }

    // ---------- 3. reviewer: a second model judges what code cannot ----------

    private void review(Task task, Project project, Playbook playbook) {
        Artifact artifact = store.latestArtifact(task.id()).orElseThrow();
        Map<String, Object> out = Json.parseObject(artifact.content());
        String system = """
                You are the code reviewer on a small software team. You never write the code yourself.
                All checks already passed. Judge what tests cannot: correctness against the acceptance criteria,
                edge cases, readability, scope creep, and whether the tests really prove the change.
                Score 1-10. 8 or more means you would approve this pull request at a good company.
                Return only JSON: {"score": 1-10, "issues": ["specific issue and how to fix it"], "summary": "2-3 lines for the human lead"}
                """ + "\nRubric for this kind of work:\n" + playbook.reviewRubric();
        String user = "Task: " + task.title() + "\n" + task.goal()
                + "\n\nPlan:\n" + task.plan() + "\n\nAcceptance criteria:\n" + task.acceptance()
                + "\n\nEngineer's summary:\n" + Obj.str(out, "summary", "")
                + "\n\nChanged files:\n" + String.join("\n", Obj.strings(out, "changes"))
                + "\n\nDiff:\n" + clip(Obj.str(out, "diff", ""), MAX_PROMPT_CHARS);
        Map<String, Object> json = askJson(task, "reviewer", system, user);

        int score = (int) Math.max(0, Math.min(10, Obj.num(json, "score", 0)));
        task.setReviewScore(score);
        store.save(task);
        List<String> issues = Obj.strings(json, "issues");
        store.addEvent(task.id(), "reviewer", "score", score + "/10" + (issues.isEmpty() ? "" : ": " + String.join("; ", issues)));

        if (score < config.reviewPassScore()) {
            sendBack(task, "Reviewer scored " + score + "/10 (needs " + config.reviewPassScore() + "):\n- "
                    + String.join("\n- ", issues));
            return;
        }
        boolean noChange = task.headCommit().equals(task.baseCommit());
        String nextStep = noChange ? "Approve to close it (report only, no code change)."
                : project.hasGithub() && github.configured() ? "Approve to push " + task.branch() + " and open a draft PR."
                : "Approve to keep branch " + task.branch() + " ready to merge in " + project.repo() + ".";
        tasks.move(task, TaskState.NEEDS_YOU, "reviewer", "scored " + score + "/10");
        tasks.raise(task, RequestKind.FINAL_APPROVAL, "Reviewer: " + score + "/10\n" + Obj.str(json, "summary", "")
                + "\n\nCommit " + TaskService.shortSha(task.headCommit()) + ", changed files:\n"
                + String.join("\n", Obj.strings(out, "changes")) + "\n\n" + nextStep);
    }

    // ---------- 4. release: only the exact commit you approved goes out ----------

    private void release(Task task, Project project) {
        Path wt = worktree(task);
        String head = git.head(wt);
        if (!head.equals(task.approvedCommit())) {
            throw new IllegalStateException("Branch moved after approval (approved " + TaskService.shortSha(task.approvedCommit())
                    + ", now " + TaskService.shortSha(head) + "). Not releasing.");
        }
        if (head.equals(task.baseCommit())) {
            store.addEvent(task.id(), "release", "report", "No code change; report kept on the task page.");
        } else if (project.hasGithub() && github.configured()) {
            git.push(wt, project.remote(), task.branch());
            String url = github.openDraftPullRequest(project.githubRepo(), task.branch(), project.baseBranch(),
                    "[botdesk #" + task.id() + "] " + task.title(), prBody(task));
            task.setPrUrl(url);
            store.addEvent(task.id(), "release", "pr", url);
        } else {
            store.addEvent(task.id(), "release", "branch", "Branch " + task.branch() + " is ready in " + project.repo()
                    + ". Review and merge with: git merge " + task.branch());
        }
        tasks.move(task, TaskState.DONE, "release", null);
        git.removeWorktree(project.repo(), wt);
    }

    @SuppressWarnings("unchecked")
    private String prBody(Task task) {
        Map<String, Object> out = store.latestArtifact(task.id()).map(a -> Json.parseObject(a.content())).orElse(Map.of());
        StringBuilder checks = new StringBuilder();
        boolean allPassed = true;
        for (Object o : Obj.list(out, "checks")) {
            Map<String, Object> c = (Map<String, Object>) o;
            boolean passed = Obj.bool(c, "passed", false);
            allPassed &= passed;
            checks.append("\n  - ").append(Obj.str(c, "name", "?")).append(passed ? ": passed" : ": FAILED");
        }
        return "## Task\n" + task.goal() + "\n\n## Plan\n" + task.plan() + "\n\n## Acceptance\n" + task.acceptance()
                + "\n\n## Engineer summary\n" + Obj.str(out, "summary", "")
                + "\n\n## Evidence\n- Checks, re-run by botdesk (not self-reported):" + checks
                + (allPassed ? "" : "\n\n> **Warning:** some checks fail. This was accepted by a human after the repair limit.")
                + "\n- Reviewer score: " + (task.reviewScore() < 0 ? "not reviewed" : task.reviewScore() + "/10")
                + "\n- Approved commit: " + task.approvedCommit()
                + "\n- Repair cycles: " + task.repairCycles();
    }

    private void cleanUpFinishedWorkspaces() {
        for (Task t : store.tasks()) {
            if (t.state().isTerminal() && t.branch() != null && Files.isDirectory(worktree(t))) {
                registry.project(t.project()).ifPresent(p -> git.removeWorktree(p.repo(), worktree(t)));
            }
        }
    }

    // ---------- shared helpers ----------

    /** Every rejection counts one repair cycle. Past the limit, a human decides. */
    private void sendBack(Task task, String feedback) {
        task.countRepairCycle();
        task.setLastFeedback(feedback);
        store.save(task);
        if (task.repairCycles() > config.maxRepairCycles()) {
            tasks.move(task, TaskState.NEEDS_YOU, "system", "repair limit reached");
            tasks.raise(task, RequestKind.ESCALATION, "Still failing after " + config.maxRepairCycles()
                    + " repair cycles. Latest problems:\n" + clip(feedback, 3_000)
                    + "\n\nApprove to accept commit " + TaskService.shortSha(task.headCommit())
                    + " as is (it does NOT pass the gates), reply with guidance, or reject.");
        } else {
            tasks.move(task, TaskState.REVISING, "system", "repair cycle " + task.repairCycles() + " of " + config.maxRepairCycles());
        }
    }

    private void fail(Task task, RequestKind kind, String message) {
        store.addEvent(task.id(), "system", "error", message);
        if (task.state().canMoveTo(TaskState.FAILED)) {
            tasks.move(task, TaskState.FAILED, "system", kind.name());
            tasks.raise(task, kind, message + "\n\nApprove or reply to retry, reject to cancel.");
        }
    }

    private Map<String, Object> askJson(Task task, String role, String system, String user) {
        LlmClient.Result r = llm.complete(new LlmClient.Request(task.id(), role,
                List.of(LlmClient.system(system), LlmClient.user(user))));
        store.addEvent(task.id(), role, "call", r.model() + String.format(" ($%.4f)", r.costUsd()));
        try {
            return Json.extractObject(r.text());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(role + " did not return JSON: " + clip(r.text(), 300), e);
        }
    }

    private Path worktree(Task task) {
        return config.workspacesDir().resolve(task.project() + "-task-" + task.id());
    }

    private String listRepo(Project project) {
        try {
            return new Workspace(project.repo(), project, Duration.ofSeconds(1)).listFiles(".");
        } catch (IOException | RuntimeException e) {
            return "(could not list files: " + e.getMessage() + ")";
        }
    }

    private String projectContext(Project project) {
        StringBuilder sb = new StringBuilder();
        for (String file : project.context()) {
            Path p = project.repo().resolve(file).normalize();
            if (!p.startsWith(project.repo())) continue;
            try {
                sb.append("## ").append(file).append("\n").append(clip(Files.readString(p), 8_000)).append("\n\n");
            } catch (IOException e) {
                sb.append("## ").append(file).append(" (missing)\n\n");
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    private static Map<String, Object> artifact(String commit, String summary, List<String> changes,
                                                List<Workspace.CheckResult> checks, String diff) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commit", commit);
        m.put("summary", summary);
        m.put("changes", changes);
        m.put("checks", checks.stream().map(c -> Map.of("name", c.name(), "passed", c.passed(),
                "exitCode", c.exitCode(), "output", clip(c.output(), 2_000))).toList());
        m.put("diff", clip(diff, 60_000));
        return m;
    }

    private static String checksLine(List<Workspace.CheckResult> checks) {
        return checks.stream().map(c -> c.name() + (c.passed() ? " ok" : " FAIL")).reduce((a, b) -> a + ", " + b).orElse("no checks");
    }

    private static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
        return sb.toString().trim();
    }

    private static String bullets(List<String> items) {
        return items.stream().map(s -> "- " + s).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n...(truncated)";
    }
}
