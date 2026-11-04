package io.github.bhushanchinmay.botdesk.domain;

import io.github.bhushanchinmay.botdesk.json.Obj;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One ticket for the software team, and its handoff contract:
 * which project, what kind of work, the goal, the lead's plan, and what "done" means (acceptance).
 * State changes only through {@link #moveTo}. Git facts (branch, commits, PR) are recorded as work happens.
 */
public final class Task {

    private final long id;
    private final String project;
    private final String kind;
    private final String title;
    private String goal;
    private String plan;
    private String acceptance;
    private TaskState state;
    private int outputVersion;
    private int repairCycles;
    private int interventions;
    private boolean confirmed;
    private String lastFeedback;
    private String branch;
    private String baseCommit;
    private String headCommit;
    private String approvedCommit;
    private String prUrl;
    private int reviewScore = -1;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant finishedAt;

    public Task(long id, String project, String kind, String title, String goal) {
        this.id = id;
        this.project = project;
        this.kind = kind;
        this.title = title;
        this.goal = goal;
        this.state = TaskState.BACKLOG;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public void moveTo(TaskState next) {
        if (!state.canMoveTo(next)) {
            throw new IllegalStateException("Task " + id + ": illegal move " + state + " -> " + next);
        }
        if (next == TaskState.RUNNING && startedAt == null) startedAt = Instant.now();
        if (next.isTerminal()) finishedAt = Instant.now();
        state = next;
        touch();
    }

    // ---------- intent-revealing updates ----------

    public void setPlan(String plan, String acceptance) { this.plan = plan; this.acceptance = acceptance; touch(); }
    public void appendToGoal(String note) { goal = goal + "\n\nUpdate from you: " + note; touch(); }
    public void startedWork(String branch, String baseCommit) { this.branch = branch; this.baseCommit = baseCommit; headCommit = baseCommit; touch(); }
    public void newOutput(String headCommit) { outputVersion++; this.headCommit = headCommit; touch(); }
    public void setReviewScore(int score) { reviewScore = score; touch(); }
    public void approve() { approvedCommit = headCommit; touch(); }
    public void setPrUrl(String url) { prUrl = url; touch(); }
    public void countRepairCycle() { repairCycles++; touch(); }
    public void resetRepairCycles() { repairCycles = 0; touch(); }
    public void countIntervention() { interventions++; touch(); }
    public void markConfirmed() { confirmed = true; touch(); }
    public void setLastFeedback(String feedback) { lastFeedback = feedback; touch(); }

    private void touch() { updatedAt = Instant.now(); }

    // ---------- reads ----------

    public long id() { return id; }
    public String project() { return project; }
    public String kind() { return kind; }
    public String title() { return title; }
    public String goal() { return goal; }
    public String plan() { return plan; }
    public String acceptance() { return acceptance; }
    public TaskState state() { return state; }
    public int outputVersion() { return outputVersion; }
    public int repairCycles() { return repairCycles; }
    public int interventions() { return interventions; }
    public boolean confirmed() { return confirmed; }
    public String lastFeedback() { return lastFeedback; }
    public String branch() { return branch; }
    public String baseCommit() { return baseCommit; }
    public String headCommit() { return headCommit; }
    public String approvedCommit() { return approvedCommit; }
    public String prUrl() { return prUrl; }
    public int reviewScore() { return reviewScore; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }

    // ---------- persistence ----------

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("project", project);
        m.put("kind", kind);
        m.put("title", title);
        m.put("goal", goal);
        m.put("plan", plan);
        m.put("acceptance", acceptance);
        m.put("state", state.name());
        m.put("outputVersion", outputVersion);
        m.put("repairCycles", repairCycles);
        m.put("interventions", interventions);
        m.put("confirmed", confirmed);
        m.put("lastFeedback", lastFeedback);
        m.put("branch", branch);
        m.put("baseCommit", baseCommit);
        m.put("headCommit", headCommit);
        m.put("approvedCommit", approvedCommit);
        m.put("prUrl", prUrl);
        m.put("reviewScore", reviewScore);
        m.put("createdAt", str(createdAt));
        m.put("updatedAt", str(updatedAt));
        m.put("startedAt", str(startedAt));
        m.put("finishedAt", str(finishedAt));
        return m;
    }

    public static Task fromMap(Map<String, Object> m) {
        Task t = new Task(Obj.num(m, "id", 0), Obj.str(m, "project", ""), Obj.str(m, "kind", ""),
                Obj.str(m, "title", ""), Obj.str(m, "goal", ""), instant(m, "createdAt"));
        t.plan = Obj.str(m, "plan", null);
        t.acceptance = Obj.str(m, "acceptance", null);
        t.state = TaskState.valueOf(Obj.str(m, "state", "BACKLOG"));
        t.outputVersion = (int) Obj.num(m, "outputVersion", 0);
        t.repairCycles = (int) Obj.num(m, "repairCycles", 0);
        t.interventions = (int) Obj.num(m, "interventions", 0);
        t.confirmed = Obj.bool(m, "confirmed", false);
        t.lastFeedback = Obj.str(m, "lastFeedback", null);
        t.branch = Obj.str(m, "branch", null);
        t.baseCommit = Obj.str(m, "baseCommit", null);
        t.headCommit = Obj.str(m, "headCommit", null);
        t.approvedCommit = Obj.str(m, "approvedCommit", null);
        t.prUrl = Obj.str(m, "prUrl", null);
        t.reviewScore = (int) Obj.num(m, "reviewScore", -1);
        t.updatedAt = instant(m, "updatedAt");
        t.startedAt = instant(m, "startedAt");
        t.finishedAt = instant(m, "finishedAt");
        return t;
    }

    private Task(long id, String project, String kind, String title, String goal, Instant createdAt) {
        this.id = id;
        this.project = project;
        this.kind = kind;
        this.title = title;
        this.goal = goal;
        this.createdAt = createdAt;
    }

    private static String str(Instant i) { return i == null ? null : i.toString(); }

    private static Instant instant(Map<String, Object> m, String key) {
        String v = Obj.str(m, key, null);
        return v == null ? null : Instant.parse(v);
    }
}
