package io.github.bhushanchinmay.botdesk.chat;

import io.github.bhushanchinmay.botdesk.domain.Records.ChatMessage;
import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService.Decision;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Talk to the leader from the dashboard or Slack.
 * Commands (approve/reject/reply/status) are handled in plain code: cheaper, and never misread.
 * Anything else goes to the leader, which can answer or create a task for a worker.
 */
public final class ChatService {

    private static final Pattern COMMAND =
            Pattern.compile("^(approve|reject|reply)\\s+#?(\\d+)\\s*(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final Store store;
    private final Registry registry;
    private final TaskService tasks;
    private final LlmClient llm;

    public ChatService(Store store, Registry registry, TaskService tasks, LlmClient llm) {
        this.store = store;
        this.registry = registry;
        this.tasks = tasks;
        this.llm = llm;
    }

    public String handle(String text, String channel) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) return "Say something, or type: help";
        store.addChat(new ChatMessage("you", trimmed, channel, Instant.now()));
        String reply;
        try {
            reply = route(trimmed);
        } catch (RuntimeException e) {
            reply = "Error: " + e.getMessage();
        }
        store.addChat(new ChatMessage("leader", reply, channel, Instant.now()));
        return reply;
    }

    private String route(String text) {
        Matcher m = COMMAND.matcher(text);
        if (m.matches()) {
            Decision decision = Decision.valueOf(m.group(1).toUpperCase(Locale.ROOT));
            return tasks.answer(Long.parseLong(m.group(2)), decision, m.group(3).isBlank() ? null : m.group(3).trim());
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.equals("help")) return help();
        if (lower.equals("status")) return status();
        return askLeader(text);
    }

    private String askLeader(String text) {
        String projects = registry.projects().stream().map(p -> "- " + p.name() + " (checks: " + p.checks().keySet() + ")")
                .collect(Collectors.joining("\n"));
        String kinds = registry.playbooks().stream().map(p -> "- " + p.kind() + ": " + p.description())
                .collect(Collectors.joining("\n"));
        String system = """
                You are the lead of Chinmay's software team of AI agents. You coordinate; you never write code here.
                Answer questions about projects and tasks using the state below. Be short and direct.
                If Chinmay asks for work, create one task: pick the project and the kind of work.
                Return only JSON: {"reply": "...", "createTask": null or {"project": "...", "kind": "...", "title": "...", "goal": "..."}}
                """ + "\nProjects:\n" + projects + "\n\nKinds of work:\n" + kinds + "\n\nCurrent state:\n" + status();

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(LlmClient.system(system));
        for (ChatMessage past : store.recentChat(10)) {
            messages.add(past.from().equals("you") ? LlmClient.user(past.text()) : LlmClient.assistant(past.text()));
        }
        // recentChat already includes the message just saved, as the last user turn.

        LlmClient.Result result = llm.complete(new LlmClient.Request(0, "lead", messages));
        Map<String, Object> json;
        try {
            json = Json.extractObject(result.text());
        } catch (IllegalArgumentException e) {
            return result.text();
        }
        String reply = Obj.str(json, "reply", "");
        Map<String, Object> create = Obj.map(json, "createTask");
        if (!create.isEmpty()) {
            Task task = tasks.create(Obj.str(create, "project", ""), Obj.str(create, "kind", ""),
                    Obj.str(create, "title", "Untitled"), Obj.str(create, "goal", text));
            reply += "\n\nCreated task #" + task.id() + " (" + task.kind() + " on " + task.project()
                    + "). The lead will plan it and ask you to approve the plan before any code is written.";
        }
        return reply;
    }

    private String status() {
        List<Task> active = store.tasks().stream().filter(t -> !t.state().isTerminal()).toList();
        StringBuilder sb = new StringBuilder();
        sb.append(active.size()).append(" active task(s).\n");
        active.forEach(t -> sb.append("- #").append(t.id()).append(" [").append(t.state()).append("] ")
                .append(t.project()).append("/").append(t.kind()).append(": ").append(t.title()).append("\n"));
        List<HumanRequest> open = store.openRequests();
        sb.append(open.size()).append(" request(s) waiting for you.\n");
        open.forEach(r -> sb.append("- request ").append(r.id()).append(" for task #").append(r.taskId())
                .append(": ").append(r.kind()).append("\n"));
        return sb.toString().trim();
    }

    private static String help() {
        return """
                Commands:
                - status: active tasks and requests waiting for you
                - approve <requestId>
                - reject <requestId> [reason]
                - reply <requestId> <text>
                Anything else goes to the leader. Ask it to do something and it will create a task.""";
    }
}
