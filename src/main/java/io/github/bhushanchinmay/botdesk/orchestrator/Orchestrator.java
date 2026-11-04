package io.github.bhushanchinmay.botdesk.orchestrator;

import io.github.bhushanchinmay.botdesk.agent.CodingEngine;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.domain.Records.RequestKind;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.BuildStage;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.PlanStage;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.ReleaseStage;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.ReviewStage;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.Stage;
import io.github.bhushanchinmay.botdesk.orchestrator.stage.StageSupport;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.quality.QualityGates;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The head chef of the software kitchen. It never writes code and knows nothing about planning or
 * reviewing: each state's work lives in a {@link Stage}. Each tick it starts due routines, picks the
 * oldest task the system (not you) can move, and runs that task's stage once.
 *
 * <pre>
 *   BACKLOG    PlanStage     lead plans                -> CONFIRMING (you approve the plan)
 *   QUEUED     BuildStage    worktree, engineer, gates -> IN_REVIEW, or REVISING with the failures
 *   REVISING   BuildStage    engineer fixes feedback   -> IN_REVIEW or REVISING (max N, then you)
 *   IN_REVIEW  ReviewStage   reviewer scores 1-10      -> NEEDS_YOU (you approve the commit) or REVISING
 *   RELEASING  ReleaseStage  approved SHA, draft PR    -> DONE
 * </pre>
 *
 * Threading: run {@link #tick} from one thread only. Steps never run concurrently, so stages need no locks.
 * To run several workers, the Store must hand out tasks with row locks first (see docs/adr/0003).
 */
public final class Orchestrator {

    private final PipelineServices services;
    private final StageSupport support;
    private final Map<TaskState, Stage> stagesByState = new EnumMap<>(TaskState.class);

    /** The standard pipeline. Most callers want this. */
    public Orchestrator(Config config, Store store, Registry registry, LlmClient llm, TaskService tasks,
                        CodingEngine engine, Git git, GitHub github) {
        this(new PipelineServices(config, store, registry, tasks, llm, engine, git, github, QualityGates.defaults()));
    }

    public Orchestrator(PipelineServices services) {
        this(services, defaultStages(new StageSupport(services)));
    }

    /** Custom pipeline: replace or add stages without touching this class. */
    public Orchestrator(PipelineServices services, List<Stage> stages) {
        this.services = services;
        this.support = new StageSupport(services);
        for (Stage stage : stages) {
            for (TaskState state : stage.handles()) {
                Stage previous = stagesByState.putIfAbsent(state, stage);
                if (previous != null) {
                    throw new IllegalArgumentException(state + " is handled by both "
                            + previous.getClass().getSimpleName() + " and " + stage.getClass().getSimpleName());
                }
            }
        }
    }

    public static List<Stage> defaultStages(StageSupport support) {
        return List.of(new PlanStage(support), new BuildStage(support), new ReviewStage(support), new ReleaseStage(support));
    }

    /** Runs due routines, then up to maxSteps task steps. Returns how many steps ran. */
    public int tick(int maxSteps) {
        startDueRoutines(Instant.now());
        int steps = 0;
        while (steps < maxSteps) {
            Optional<Task> next = nextActionable();
            if (next.isEmpty()) break;
            step(next.get());
            steps++;
        }
        cleanUpFinishedWorkspaces();
        return steps;
    }

    // TODO: a task that was RUNNING when the process died is never picked up again, because RUNNING has
    //  no stage (it is "in flight"). Add a heartbeat per step and a reconciler that moves stale RUNNING
    //  tasks to FAILED, where one click retries them and the worktree keeps the earlier work.
    private Optional<Task> nextActionable() {
        return services.store().tasks().stream()
                .filter(t -> stagesByState.containsKey(t.state()))
                .min(Comparator.comparing(Task::updatedAt));
    }

    void startDueRoutines(Instant now) {
        for (Config.Routine routine : services.config().routines()) {
            if (!routine.enabled()) continue;
            Optional<Instant> last = services.store().lastRoutineRun(routine.name());
            if (last.isEmpty() || Duration.between(last.get(), now).toHours() >= routine.everyHours()) {
                services.store().markRoutineRun(routine.name(), now);
                services.tasks().createPreConfirmed(routine.project(), routine.kind(), routine.title(), routine.goal());
            }
        }
    }

    void step(Task task) {
        try {
            Project project = services.registry().project(task.project())
                    .orElseThrow(() -> new IllegalStateException("Project '" + task.project() + "' is not attached"));
            Playbook playbook = services.registry().playbook(task.kind())
                    .orElseThrow(() -> new IllegalStateException("No playbook for kind '" + task.kind() + "'"));
            stagesByState.get(task.state()).run(task, project, playbook);
        } catch (LlmClient.BudgetExceededException e) {
            fail(task, RequestKind.BUDGET, e.getMessage());
        } catch (RuntimeException e) {
            fail(task, RequestKind.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** Any stage error becomes a FAILED task with a request, so you decide: retry or cancel. */
    private void fail(Task task, RequestKind kind, String message) {
        services.store().addEvent(task.id(), "system", "error", message);
        if (task.state().canMoveTo(TaskState.FAILED)) {
            services.tasks().move(task, TaskState.FAILED, "system", kind.name());
            services.tasks().raise(task, kind, message + "\n\nApprove or reply to retry, reject to cancel.");
        }
    }

    private void cleanUpFinishedWorkspaces() {
        for (Task t : services.store().tasks()) {
            if (t.state().isTerminal() && t.branch() != null && Files.isDirectory(support.worktree(t))) {
                services.registry().project(t.project()).ifPresent(p -> services.git().removeWorktree(p.repo(), support.worktree(t)));
            }
        }
    }
}
