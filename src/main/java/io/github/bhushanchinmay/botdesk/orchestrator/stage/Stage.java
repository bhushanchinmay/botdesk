package io.github.bhushanchinmay.botdesk.orchestrator.stage;

import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;

import java.util.Set;

/**
 * One step of the pipeline: what the system does for a task in a given state.
 *
 * <p>A stage must leave the task in a different state when it returns (or throw), otherwise the
 * orchestrator would pick the same task again. It should be safe to run again after a crash:
 * read what is already done (branch, commits) before doing it a second time.
 *
 * <p>Exceptions are fine: the orchestrator turns them into a FAILED task with a retry request.
 */
public interface Stage {

    /** The states this stage moves forward. Each state belongs to at most one stage. */
    Set<TaskState> handles();

    void run(Task task, Project project, Playbook playbook);
}
