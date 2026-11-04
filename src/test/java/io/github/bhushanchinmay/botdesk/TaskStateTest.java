package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.domain.TaskState;

import static io.github.bhushanchinmay.botdesk.TestRunner.check;
import static io.github.bhushanchinmay.botdesk.TestRunner.throwsAny;

public final class TaskStateTest {

    public static void testHappyPathIsLegal() {
        Task t = new Task(1, "calc", "bugfix", "t", "g");
        for (TaskState s : new TaskState[]{TaskState.CONFIRMING, TaskState.QUEUED, TaskState.RUNNING,
                TaskState.IN_REVIEW, TaskState.NEEDS_YOU, TaskState.RELEASING, TaskState.DONE}) {
            t.moveTo(s);
        }
        check(t.state() == TaskState.DONE && t.finishedAt() != null && t.startedAt() != null, "reached DONE");
    }

    public static void testCodeCannotShipWithoutReviewAndApproval() {
        Task t = new Task(1, "calc", "bugfix", "t", "g");
        throwsAny(() -> t.moveTo(TaskState.DONE), "BACKLOG -> DONE");
        t.moveTo(TaskState.CONFIRMING);
        t.moveTo(TaskState.QUEUED);
        t.moveTo(TaskState.RUNNING);
        throwsAny(() -> t.moveTo(TaskState.RELEASING), "RUNNING -> RELEASING skips review and approval");
        t.moveTo(TaskState.IN_REVIEW);
        throwsAny(() -> t.moveTo(TaskState.RELEASING), "IN_REVIEW -> RELEASING skips your approval");
    }

    public static void testTerminalStatesAreFinal() {
        for (TaskState from : TaskState.values()) {
            if (!from.isTerminal()) continue;
            for (TaskState to : TaskState.values()) check(!from.canMoveTo(to), from + " must not move to " + to);
        }
    }
}
