package io.github.bhushanchinmay.botdesk.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Every task is in exactly one state. All moves go through {@link #canMoveTo}
 * so an illegal jump (for example RUNNING straight to DONE) fails loudly.
 *
 * BACKLOG -lead plans-> CONFIRMING -you-> QUEUED -> RUNNING (engineer) -gates-> IN_REVIEW (reviewer)
 *   -> NEEDS_YOU (final approval) -you-> RELEASING (push + PR) -> DONE
 */
public enum TaskState {
    BACKLOG("Will be done"),
    CONFIRMING("Needs you"),
    QUEUED("Will be done"),
    RUNNING("In progress"),
    IN_REVIEW("In progress"),
    REVISING("In progress"),
    NEEDS_YOU("Needs you"),
    RELEASING("In progress"),
    DONE("Done"),
    FAILED("Needs you"),
    CANCELLED("Done");

    private final String column;

    TaskState(String column) { this.column = column; }

    /** The dashboard column this state is shown in. */
    public String column() { return column; }

    public boolean isTerminal() { return this == DONE || this == CANCELLED; }

    private static final Map<TaskState, Set<TaskState>> ALLOWED = Map.ofEntries(
            Map.entry(BACKLOG, EnumSet.of(CONFIRMING, QUEUED, FAILED, CANCELLED)),
            Map.entry(CONFIRMING, EnumSet.of(QUEUED, BACKLOG, CANCELLED)),
            Map.entry(QUEUED, EnumSet.of(RUNNING, CANCELLED)),
            Map.entry(RUNNING, EnumSet.of(IN_REVIEW, REVISING, NEEDS_YOU, FAILED)),
            Map.entry(IN_REVIEW, EnumSet.of(REVISING, NEEDS_YOU, FAILED)),
            Map.entry(REVISING, EnumSet.of(IN_REVIEW, REVISING, NEEDS_YOU, FAILED)),
            Map.entry(NEEDS_YOU, EnumSet.of(RELEASING, REVISING, CANCELLED)),
            Map.entry(RELEASING, EnumSet.of(DONE, FAILED)),
            Map.entry(FAILED, EnumSet.of(QUEUED, BACKLOG, RELEASING, CANCELLED)),
            Map.entry(DONE, EnumSet.noneOf(TaskState.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(TaskState.class)));

    public boolean canMoveTo(TaskState next) {
        return ALLOWED.get(this).contains(next);
    }
}
