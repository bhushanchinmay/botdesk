package io.github.bhushanchinmay.botdesk.agent;

/**
 * Whatever actually edits code inside a workspace. The built-in {@link ToolLoopAgent} is one
 * implementation; an external agent (OpenCode, aider, Claude Code) could be another, without
 * changing the pipeline around it.
 */
public interface CodingEngine {

    /** role: which model role to use (for example "engineer"). */
    record Job(long taskId, String role, String instructions, String assignment, Workspace workspace, int maxSteps) {}

    /** finished=false means the step limit was hit before the agent called finish. */
    record Outcome(String summary, int steps, boolean finished) {}

    Outcome run(Job job);
}
