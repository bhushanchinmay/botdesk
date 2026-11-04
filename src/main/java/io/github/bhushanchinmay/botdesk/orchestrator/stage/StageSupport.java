package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.agent.Workspace;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.orchestrator.PipelineServices;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** Helpers shared by stages: model calls that must return JSON, the repair loop, and project context. */
public final class StageSupport {

    static final int MAX_PROMPT_CHARS = 24_000;
    private static final int MAX_CONTEXT_FILE_CHARS = 8_000;

    private final PipelineServices services;

    public StageSupport(PipelineServices services) { this.services = services; }

    public PipelineServices services() { return services; }

    /** Where a task's git worktree lives. Stable for the task's whole life, so retries reuse earlier work. */
    public Path worktree(Task task) {
        return services.config().workspacesDir().resolve(task.project() + "-task-" + task.id());
    }

    /** Asks a role a question whose answer must be one JSON object. Logs the call on the timeline. */
    public Map<String, Object> askJson(Task task, String role, String system, String user) {
        LlmClient.Result r = services.llm().complete(new LlmClient.Request(task.id(), role,
                List.of(LlmClient.system(system), LlmClient.user(user))));
        services.store().addEvent(task.id(), role, "call", r.model() + String.format(" ($%.4f)", r.costUsd()));
        try {
            return Json.extractObject(r.text());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(role + " did not return JSON: " + clip(r.text(), 300), e);
        }
    }

    /** Every rejection counts one repair cycle. Past the limit, a human decides. */
    public void sendBack(Task task, String feedback) {
        int limit = services.config().maxRepairCycles();
        task.countRepairCycle();
        task.setLastFeedback(feedback);
        services.store().save(task);
        if (task.repairCycles() > limit) {
            services.tasks().move(task, TaskState.NEEDS_YOU, "system", "repair limit reached");
            services.tasks().raise(task, RequestKind.ESCALATION, "Still failing after " + limit
                    + " repair cycles. Latest problems:\n" + clip(feedback, 3_000)
                    + "\n\nApprove to accept commit " + shortSha(task.headCommit())
                    + " as is (it does NOT pass the gates), reply with guidance, or reject.");
        } else {
            services.tasks().move(task, TaskState.REVISING, "system", "repair cycle " + task.repairCycles() + " of " + limit);
        }
    }

    public String listRepo(Project project) {
        try {
            return new Workspace(project.repo(), project, Duration.ofSeconds(1)).listFiles(".");
        } catch (IOException | RuntimeException e) {
            return "(could not list files: " + e.getMessage() + ")";
        }
    }

    /** The files a project names in its "context" list (README, architecture notes), clipped. */
    public String projectContext(Project project) {
        StringBuilder sb = new StringBuilder();
        for (String file : project.context()) {
            Path p = project.repo().resolve(file).normalize();
            if (!p.startsWith(project.repo())) continue;
            try {
                sb.append("## ").append(file).append("\n").append(clip(Files.readString(p), MAX_CONTEXT_FILE_CHARS)).append("\n\n");
            } catch (IOException e) {
                sb.append("## ").append(file).append(" (missing)\n\n");
            }
        }
        return sb.isEmpty() ? "(none)" : sb.toString();
    }

    /** Routine tasks are pre-approved in config and skip the plan step, so they have no plan. */
    static String planOrDefault(Task task) {
        return task.plan() != null ? task.plan() : "(no separate plan: routine task; follow the goal and the guide)";
    }

    static String acceptanceOrDefault(Task task) {
        return task.acceptance() != null ? task.acceptance() : "- All project checks pass";
    }

    public static String shortSha(String sha) { return sha == null ? "-" : sha.substring(0, Math.min(10, sha.length())); }

    public static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "\n...(truncated)";
    }

    static String numbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) sb.append(i + 1).append(". ").append(items.get(i)).append("\n");
        return sb.toString().trim();
    }

    static String bullets(List<String> items) {
        return items.stream().map(s -> "- " + s).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }
}
