package io.github.bhushanchinmay.botdesk.store;

import io.github.bhushanchinmay.botdesk.domain.Records.Artifact;
import io.github.bhushanchinmay.botdesk.domain.Records.ChatMessage;
import io.github.bhushanchinmay.botdesk.domain.Records.Event;
import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Records.ModelCall;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * The task store: single source of truth that bots write to and the dashboard reads.
 * Kept in memory and saved to one JSON file after every change (write to temp file, then
 * atomic rename, so a crash never leaves a half-written file).
 * Enough for one person's bots. Swap for SQLite/Postgres behind the same methods if it grows.
 */
public final class Store {

    private final Path file;
    private final Map<Long, Task> tasks = new LinkedHashMap<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<Long, HumanRequest> requests = new LinkedHashMap<>();
    private final List<Artifact> artifacts = new ArrayList<>();
    private final List<ModelCall> calls = new ArrayList<>();
    private final List<ChatMessage> chat = new ArrayList<>();
    private final Map<String, Instant> routineRuns = new LinkedHashMap<>();
    private long nextTaskId = 1; // task numbers you see: #1, #2, #3
    private long nextId = 1;     // everything else: events, requests

    public Store(Path dataDir) {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.file = dataDir.resolve("store.json");
        load();
    }

    // ---------- tasks ----------

    public synchronized Task createTask(String project, String kind, String title, String goal) {
        Task task = new Task(nextTaskId++, project, kind, title, goal);
        tasks.put(task.id(), task);
        addEventInternal(task.id(), "you", "created", title);
        flush();
        return task;
    }

    public synchronized Optional<Task> task(long id) { return Optional.ofNullable(tasks.get(id)); }

    public synchronized List<Task> tasks() { return List.copyOf(tasks.values()); }

    /** Call after mutating a task so the change is persisted. */
    public synchronized void save(Task task) {
        tasks.put(task.id(), task);
        flush();
    }

    // ---------- events ----------

    public synchronized void addEvent(long taskId, String actor, String kind, String detail) {
        addEventInternal(taskId, actor, kind, detail);
        flush();
    }

    private void addEventInternal(long taskId, String actor, String kind, String detail) {
        events.add(new Event(nextId++, taskId, Instant.now(), actor, kind, detail));
    }

    public synchronized List<Event> events(long taskId) {
        return events.stream().filter(e -> e.taskId() == taskId).toList();
    }

    // ---------- requests for you ----------

    public synchronized HumanRequest raise(long taskId, RequestKind kind, String prompt, int outputVersion) {
        HumanRequest r = new HumanRequest(nextId++, taskId, kind, prompt, outputVersion, true, null, null, Instant.now());
        requests.put(r.id(), r);
        flush();
        return r;
    }

    public synchronized Optional<HumanRequest> request(long id) { return Optional.ofNullable(requests.get(id)); }

    public synchronized List<HumanRequest> openRequests() {
        return requests.values().stream().filter(HumanRequest::open).toList();
    }

    public synchronized List<HumanRequest> requests(long taskId) {
        return requests.values().stream().filter(r -> r.taskId() == taskId).toList();
    }

    public synchronized void update(HumanRequest r) {
        requests.put(r.id(), r);
        flush();
    }

    // ---------- artifacts ----------

    public synchronized Artifact addArtifact(long taskId, int version, String content) {
        Artifact a = new Artifact(taskId, version, sha256(content), content, Instant.now());
        artifacts.add(a);
        flush();
        return a;
    }

    public synchronized Optional<Artifact> latestArtifact(long taskId) {
        return artifacts.stream().filter(a -> a.taskId() == taskId).max(Comparator.comparingInt(Artifact::version));
    }

    // ---------- cost ----------

    public synchronized void recordCall(ModelCall call) {
        calls.add(call);
        flush();
    }

    public synchronized double spendSince(Instant since) {
        return calls.stream().filter(c -> !c.at().isBefore(since)).mapToDouble(ModelCall::costUsd).sum();
    }

    public synchronized double taskCost(long taskId) {
        return calls.stream().filter(c -> c.taskId() == taskId).mapToDouble(ModelCall::costUsd).sum();
    }

    public synchronized List<ModelCall> calls(long taskId) {
        return calls.stream().filter(c -> c.taskId() == taskId).toList();
    }

    // ---------- chat ----------

    public synchronized void addChat(ChatMessage m) {
        chat.add(m);
        flush();
    }

    public synchronized List<ChatMessage> recentChat(int limit) {
        return List.copyOf(chat.subList(Math.max(0, chat.size() - limit), chat.size()));
    }

    // ---------- routines ----------

    public synchronized Optional<Instant> lastRoutineRun(String name) { return Optional.ofNullable(routineRuns.get(name)); }

    public synchronized void markRoutineRun(String name, Instant at) {
        routineRuns.put(name, at);
        flush();
    }

    // ---------- persistence ----------

    private void flush() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nextTaskId", nextTaskId);
        root.put("nextId", nextId);
        root.put("tasks", tasks.values().stream().map(Task::toMap).toList());
        root.put("events", events.stream().map(Event::toMap).toList());
        root.put("requests", requests.values().stream().map(HumanRequest::toMap).toList());
        root.put("artifacts", artifacts.stream().map(Artifact::toMap).toList());
        root.put("calls", calls.stream().map(ModelCall::toMap).toList());
        root.put("chat", chat.stream().map(ChatMessage::toMap).toList());
        Map<String, Object> runs = new LinkedHashMap<>();
        routineRuns.forEach((k, v) -> runs.put(k, v.toString()));
        root.put("routineRuns", runs);
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(root), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save store", e);
        }
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            nextTaskId = Obj.num(root, "nextTaskId", 1);
            nextId = Obj.num(root, "nextId", 1);
            each(root, "tasks", Task::fromMap).forEach(t -> tasks.put(t.id(), t));
            events.addAll(each(root, "events", Event::fromMap));
            each(root, "requests", HumanRequest::fromMap).forEach(r -> requests.put(r.id(), r));
            artifacts.addAll(each(root, "artifacts", Artifact::fromMap));
            calls.addAll(each(root, "calls", ModelCall::fromMap));
            chat.addAll(each(root, "chat", ChatMessage::fromMap));
            Obj.map(root, "routineRuns").forEach((k, v) -> routineRuns.put(k, Instant.parse(v.toString())));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load store", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> each(Map<String, Object> root, String key, Function<Map<String, Object>, T> fn) {
        return Obj.list(root, key).stream().map(o -> fn.apply((Map<String, Object>) o)).toList();
    }

    public static String sha256(String content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
