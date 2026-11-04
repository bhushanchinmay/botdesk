package io.github.bhushanchinmay.botdesk.orchestrator;

import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.store.Store;

/**
 * The one place where task state changes and where your answers are applied.
 * The dashboard, chat and Slack all call this, so the rules are the same everywhere.
 */
public final class TaskService {

    public enum Decision { APPROVE, REJECT, REPLY }

    private final Store store;
    private final Registry registry;
    private volatile Notifier notifier = Notifier.NONE;

    public TaskService(Store store, Registry registry) {
        this.store = store;
        this.registry = registry;
    }

    public void setNotifier(Notifier notifier) { this.notifier = notifier; }

    /** New tasks start in BACKLOG; the lead plans them and asks you to confirm. */
    public Task create(String project, String kind, String title, String goal) {
        registry.project(project).orElseThrow(() -> new IllegalArgumentException("Unknown project: " + project));
        registry.playbook(kind).orElseThrow(() -> new IllegalArgumentException("Unknown kind of work: " + kind));
        if (title == null || title.isBlank()) throw new IllegalArgumentException("A task needs a title");
        return store.createTask(project, kind, title.trim(), goal == null ? "" : goal.trim());
    }

    /** Routine tasks skip confirmation: you approved the routine once, in config. */
    public synchronized Task createPreConfirmed(String project, String kind, String title, String goal) {
        Task task = create(project, kind, title, goal);
        task.markConfirmed();
        move(task, TaskState.QUEUED, "system", "routine task, pre-confirmed");
        return task;
    }

    public synchronized void move(Task task, TaskState next, String actor, String detail) {
        TaskState from = task.state();
        task.moveTo(next);
        store.save(task);
        store.addEvent(task.id(), actor, "state", from + " -> " + next + (detail == null ? "" : ": " + detail));
    }

    public synchronized HumanRequest raise(Task task, RequestKind kind, String prompt) {
        HumanRequest r = store.raise(task.id(), kind, prompt, task.outputVersion());
        notifier.notify("Needs you: task #" + task.id() + " (" + task.title() + ") " + kind
                + ". Reply in the dashboard, or here with: approve " + r.id() + " | reject " + r.id()
                + " | reply " + r.id() + " <text>");
        return r;
    }

    /**
     * Applies your answer. Returns a short message for the UI.
     * Approval belongs to one exact output: if the code changed after the request was raised,
     * the approval is refused and must be given again.
     */
    public synchronized String answer(long requestId, Decision decision, String text) {
        HumanRequest r = store.request(requestId).orElseThrow(() -> new IllegalArgumentException("No request " + requestId));
        if (!r.open()) return "Request " + requestId + " is already closed.";
        Task task = store.task(r.taskId()).orElseThrow();
        if (decision == Decision.REPLY && (text == null || text.isBlank())) return "A reply needs some text.";
        boolean approvesCode = r.kind() == RequestKind.FINAL_APPROVAL || r.kind() == RequestKind.ESCALATION;
        if (decision == Decision.APPROVE && approvesCode && r.outputVersion() != task.outputVersion()) {
            return "Refused: the code changed from v" + r.outputVersion() + " to v" + task.outputVersion()
                    + " after this request. Review the new version.";
        }

        store.update(r.close(decision.name(), text));
        task.countIntervention();
        store.addEvent(task.id(), "you", decision.name().toLowerCase(), text == null || text.isBlank() ? r.kind().name() : text);

        switch (r.kind()) {
            case CONFIRM_TASK -> {
                switch (decision) {
                    case APPROVE -> { task.markConfirmed(); move(task, TaskState.QUEUED, "you", "plan approved"); }
                    case REPLY -> { task.appendToGoal(text); move(task, TaskState.BACKLOG, "you", "clarified, plan again"); }
                    case REJECT -> move(task, TaskState.CANCELLED, "you", text);
                }
            }
            case FINAL_APPROVAL, ESCALATION -> {
                switch (decision) {
                    case APPROVE -> {
                        task.approve();
                        move(task, TaskState.RELEASING, "you", "approved commit " + shortSha(task.approvedCommit()));
                    }
                    case REPLY -> {
                        task.setLastFeedback("From your human lead: " + text);
                        task.resetRepairCycles();
                        move(task, TaskState.REVISING, "you", "sent back with feedback");
                    }
                    case REJECT -> move(task, TaskState.CANCELLED, "you", text);
                }
            }
            case ERROR, BUDGET -> {
                if (decision == Decision.REJECT) {
                    move(task, TaskState.CANCELLED, "you", text);
                } else {
                    if (text != null && !text.isBlank()) task.setLastFeedback("From your human lead: " + text);
                    TaskState retry = task.approvedCommit() != null ? TaskState.RELEASING
                            : task.confirmed() ? TaskState.QUEUED : TaskState.BACKLOG;
                    move(task, retry, "you", "retry");
                }
            }
        }
        store.save(task);
        String verb = switch (decision) {
            case APPROVE -> "approved";
            case REJECT -> "rejected";
            case REPLY -> "answered";
        };
        return "Done: request " + requestId + " " + verb + ". Task #" + task.id() + " is now " + task.state() + ".";
    }

    public static String shortSha(String sha) { return sha == null ? "-" : sha.substring(0, Math.min(10, sha.length())); }
}
