package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.ToolLoopAgent;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.orchestrator.Orchestrator;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService.Decision;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.github.bhushanchinmay.botdesk.Fixtures.FakeLlm.call;
import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.eq;

/** The whole team flow on a real git repo, with a scripted model: no network, no cost. */
public final class PipelineTest {

    private static final String PLAN = "{\"restatement\":\"add() subtracts\",\"plan\":[\"failing test\",\"fix\"],"
            + "\"acceptance\":[\"add(2,3)=5\",\"test added\"],\"questions\":[]}";
    private static final String PASS = "{\"score\":9,\"issues\":[],\"summary\":\"Correct fix with a test.\"}";
    private static final String WEAK = "{\"score\":5,\"issues\":[\"test name is unclear\"],\"summary\":\"meh\"}";

    record Rig(Config config, Store store, TaskService tasks, Orchestrator orch, Fixtures.FakeLlm llm, Project project) {}

    static Rig rig(Fixtures.FakeLlm llm, String githubRepo, GitHub github) {
        Path dir = Fixtures.tempDir();
        Config config = Fixtures.config(dir, "http://unused", 10);
        Project project = Fixtures.gitProject(dir, githubRepo);
        Store store = new Store(config.dataDir());
        var registry = Fixtures.registry(project);
        TaskService tasks = new TaskService(store, registry);
        Orchestrator orch = new Orchestrator(config, store, registry, llm, tasks, new ToolLoopAgent(llm, store),
                new Git(config.git()), github);
        return new Rig(config, store, tasks, orch, llm, project);
    }

    static Rig rig(Fixtures.FakeLlm llm) { return rig(llm, "", new GitHub(null)); }

    /** The engineer turns that correctly fix the bug, add a test, run it, and finish. */
    static void goodEngineer(Fixtures.FakeLlm llm) {
        llm.tools("engineer", call("read_file", "path", "src/calc.txt"))
           .tools("engineer", call("replace_in_file", "path", "src/calc.txt", "old_text", "a - b", "new_text", "a + b"),
                              call("write_file", "path", "tests/add_test.txt", "content", "add(2,3) == 5"))
           .tools("engineer", call("run_check", "name", "test"))
           .tools("engineer", call("finish", "summary", "add() used minus. Fixed and added a test."));
    }

    static HumanRequest onlyOpen(Store store) {
        List<HumanRequest> open = store.openRequests();
        eq(1, open.size(), "exactly one open request: " + open);
        return open.get(0);
    }

    static void approvePlan(Rig r) {
        r.orch().tick(5);
        eq(RequestKind.CONFIRM_TASK, onlyOpen(r.store()).kind(), "lead asks to confirm the plan");
        r.tasks().answer(onlyOpen(r.store()).id(), Decision.APPROVE, null);
    }

    public static void testBugfixFlowShipsAReviewedBranch() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN).text("reviewer", PASS);
        goodEngineer(llm);
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "bugfix", "add() subtracts", "add(2,3) returns -1");

        r.orch().tick(5);
        eq(TaskState.CONFIRMING, task.state(), "no code before the plan is approved");
        eq(0, llm.calls("engineer"), "engineer has not started");
        check(task.plan().contains("1. failing test"), "plan saved");
        r.tasks().answer(onlyOpen(r.store()).id(), Decision.APPROVE, null);

        r.orch().tick(10);
        eq(TaskState.NEEDS_YOU, task.state(), "waits for your approval after review");
        eq(9, task.reviewScore(), "review score saved");
        HumanRequest approval = onlyOpen(r.store());
        eq(RequestKind.FINAL_APPROVAL, approval.kind(), "final approval");
        check(approval.prompt().contains("src/calc.txt"), "approval shows changed files");

        Map<String, Object> out = Json.parseObject(r.store().latestArtifact(task.id()).orElseThrow().content());
        check(Obj.str(out, "diff", "").contains("+add(a, b) = a + b"), "diff recorded");
        check(Obj.list(out, "checks").stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("passed"))),
                "checks re-run and passed");
        check(r.store().events(task.id()).stream().anyMatch(e -> e.kind().equals("tool") && e.detail().startsWith("replace_in_file")),
                "tool calls logged on the timeline");

        r.tasks().answer(approval.id(), Decision.APPROVE, null);
        eq(TaskState.RELEASING, task.state(), "approved");
        r.orch().tick(5);
        eq(TaskState.DONE, task.state(), "released");

        Path repo = r.project().repo();
        check(Fixtures.sh(repo, "cat src/calc.txt").contains("a - b"), "main checkout untouched");
        eq("main", Fixtures.sh(repo, "git rev-parse --abbrev-ref HEAD").trim(), "still on main");
        check(Fixtures.sh(repo, "git show botdesk/task-1:src/calc.txt").contains("a + b"), "fix lives on the task branch");
        eq("Chinmay Bhushan <21293962+bhushanchinmay@users.noreply.github.com>",
                Fixtures.sh(repo, "git log -1 --format='%an <%ae>' botdesk/task-1").trim(), "commit author from config");
        check(!Fixtures.sh(repo, "git log -1 --format=%B botdesk/task-1").contains("Co-Authored"), "no co-author trailer");
        check(!Files.exists(r.config().workspacesDir().resolve("calc-task-1")), "worktree cleaned up");
    }

    public static void testFailingGatesRepairThenEscalate() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN);
        // Attempt 1 tries to cheat by rewriting the test runner; attempts 2 and 3 just give up.
        llm.tools("engineer", call("write_file", "path", "test.sh", "content", "exit 0"))
           .tools("engineer", call("finish", "summary", "done"))
           .tools("engineer", call("finish", "summary", "still stuck"))
           .tools("engineer", call("finish", "summary", "still stuck"));
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "bugfix", "add() subtracts", "fix it");
        approvePlan(r);
        r.orch().tick(20);

        check(r.store().events(task.id()).stream().anyMatch(e -> e.detail().contains("REFUSED")),
                "cheating on the protected test runner was refused");
        eq(3, task.outputVersion(), "three attempts");
        eq(TaskState.NEEDS_YOU, task.state(), "escalated after the repair limit");
        eq(RequestKind.ESCALATION, onlyOpen(r.store()).kind(), "escalation request");
        check(onlyOpen(r.store()).prompt().contains("does NOT pass the gates"), "escalation warns that checks fail");
        check(task.lastFeedback().contains("Check 'test' failed"), "feedback carries the real check output");
        String secondPrompt = (String) llm.requests.stream().filter(q -> q.role().equals("engineer")).toList()
                .get(2).messages().get(1).get("content");
        check(secondPrompt.contains("It was sent back"), "revision prompt includes the feedback");
        eq(0, llm.calls("reviewer"), "reviewer never sees code that fails the gates");
    }

    public static void testLowReviewScoreSendsBack() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN).text("reviewer", WEAK, PASS);
        goodEngineer(llm);
        llm.tools("engineer", call("finish", "summary", "Renamed nothing; test name is fine as is."));
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "bugfix", "add() subtracts", "fix it");
        approvePlan(r);
        r.orch().tick(20);
        check(r.store().events(task.id()).stream().anyMatch(e -> e.kind().equals("score") && e.detail().startsWith("5/10")),
                "low score logged");
        eq(1, task.repairCycles(), "one repair cycle from review");
        eq(9, task.reviewScore(), "second review passed");
        eq(TaskState.NEEDS_YOU, task.state(), "now waiting for you");
    }

    public static void testReleaseRefusesCodeChangedAfterApproval() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN).text("reviewer", PASS);
        goodEngineer(llm);
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "bugfix", "add() subtracts", "fix it");
        approvePlan(r);
        r.orch().tick(10);
        r.tasks().answer(onlyOpen(r.store()).id(), Decision.APPROVE, null);

        Path wt = r.config().workspacesDir().resolve("calc-task-1");
        Fixtures.sh(wt, "echo sneaky >> src/calc.txt && git -c user.name=x -c user.email=x@x commit -qam sneaky");
        r.orch().tick(5);
        eq(TaskState.FAILED, task.state(), "release stopped");
        check(onlyOpen(r.store()).prompt().contains("Branch moved after approval"), "you are told why");
    }

    public static void testReleaseOpensDraftPullRequest() throws Exception {
        try (Fixtures.StubServer gh = new Fixtures.StubServer()
                .on("/repos/me/calc/pulls", 201, "{\"html_url\":\"https://github.com/me/calc/pull/7\"}")) {
            Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN).text("reviewer", PASS);
            goodEngineer(llm);
            Rig r = rig(llm, "me/calc", new GitHub("ghp_test", gh.url()));
            Path remote = r.project().repo().resolveSibling("remote.git");
            Fixtures.sh(r.project().repo(), "git init -q --bare " + remote + " && git remote add origin " + remote);
            Task task = r.tasks().create("calc", "bugfix", "add() subtracts", "fix it");
            approvePlan(r);
            r.orch().tick(10);
            check(onlyOpen(r.store()).prompt().contains("open a draft PR"), "approval says what will happen");
            r.tasks().answer(onlyOpen(r.store()).id(), Decision.APPROVE, null);
            r.orch().tick(5);

            eq(TaskState.DONE, task.state(), "done");
            eq("https://github.com/me/calc/pull/7", task.prUrl(), "PR link saved");
            check(Fixtures.sh(remote.getParent(), "git --git-dir=" + remote + " branch").contains("botdesk/task-1"), "branch pushed");
            String body = gh.bodies.get(0);
            check(body.contains("\"draft\":true") && body.contains("\"head\":\"botdesk/task-1\""), "draft PR from the task branch");
            check(body.contains("Reviewer score: 9/10") && body.contains(task.approvedCommit()), "PR carries the evidence");
            check(body.contains("test: passed") && !body.contains("Warning"), "PR lists real check results");
            eq(List.of("Bearer ghp_test"), gh.headers.get(0).get("Authorization"), "token used");
        }
    }

    public static void testInvestigateAnswersWithoutChangingCode() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN).text("reviewer", PASS);
        llm.tools("engineer", call("search", "regex", "a - b"))
           .tools("engineer", call("finish", "summary", "add() subtracts at src/calc.txt:1. Fix: use a + b."));
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "investigate", "Why is add wrong?", "Explain the bug");
        approvePlan(r);
        r.orch().tick(10);
        r.tasks().answer(onlyOpen(r.store()).id(), Decision.APPROVE, null);
        r.orch().tick(5);
        eq(TaskState.DONE, task.state(), "answered even though the build is red");
        check(Json.parseObject(r.store().latestArtifact(task.id()).orElseThrow().content()).get("summary")
                .toString().contains("src/calc.txt:1"), "answer kept on the task");
        check(r.store().events(task.id()).stream().anyMatch(e -> e.kind().equals("report")), "no release of code");
    }

    public static void testRestartKeepsState() {
        Fixtures.FakeLlm llm = new Fixtures.FakeLlm().text("lead", PLAN);
        Rig r = rig(llm);
        Task task = r.tasks().create("calc", "bugfix", "t", "g");
        r.orch().tick(5);
        Store reloaded = new Store(r.config().dataDir());
        Task again = reloaded.task(task.id()).orElseThrow();
        eq(TaskState.CONFIRMING, again.state(), "state survives restart");
        eq(task.plan(), again.plan(), "plan survives restart");
        eq(1, reloaded.openRequests().size(), "request survives restart");
    }
}
