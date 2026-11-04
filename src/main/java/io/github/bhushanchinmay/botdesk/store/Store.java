package io.github.bhushanchinmay.botdesk.store;

import io.github.bhushanchinmay.botdesk.domain.Records.Artifact;
import io.github.bhushanchinmay.botdesk.domain.Records.ChatMessage;
import io.github.bhushanchinmay.botdesk.domain.Records.Event;
import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Records.ModelCall;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The single source of truth. The pipeline writes to it; the dashboard, chat and Slack read from it.
 *
 * <p>Implementations must be safe to call from several threads. Every write must be durable before
 * the method returns, because a task's state on disk is what makes a restart safe.
 *
 * <p>{@link #task} and {@link #tasks} return the live {@link Task} objects, not copies; see the
 * threading note on {@code Task}. A database implementation would return fresh objects per call and
 * guard updates with a version column instead.
 *
 * <p>Today: {@link JsonFileStore}. A Postgres implementation would add row locking
 * ({@code SELECT ... FOR UPDATE SKIP LOCKED}) so several orchestrator workers can share one store.
 */
public interface Store {

    // ---------- tasks ----------

    Task createTask(String project, String kind, String title, String goal);

    Optional<Task> task(long id);

    List<Task> tasks();

    /** Persists a task after it was mutated. Callers must save after every change they want to keep. */
    void save(Task task);

    // ---------- timeline ----------

    void addEvent(long taskId, String actor, String kind, String detail);

    List<Event> events(long taskId);

    // ---------- requests for the human ----------

    HumanRequest raise(long taskId, RequestKind kind, String prompt, int outputVersion);

    Optional<HumanRequest> request(long id);

    List<HumanRequest> openRequests();

    List<HumanRequest> requests(long taskId);

    void update(HumanRequest request);

    // ---------- outputs ----------

    Artifact addArtifact(long taskId, int version, String content);

    Optional<Artifact> latestArtifact(long taskId);

    // ---------- cost ----------

    void recordCall(ModelCall call);

    double spendSince(Instant since);

    double taskCost(long taskId);

    List<ModelCall> calls(long taskId);

    // ---------- chat ----------

    void addChat(ChatMessage message);

    List<ChatMessage> recentChat(int limit);

    // ---------- routines ----------

    Optional<Instant> lastRoutineRun(String name);

    void markRoutineRun(String name, Instant at);
}
