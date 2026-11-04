/**
 * The engineer's sandbox and loop. {@link io.github.bhushanchinmay.botdesk.agent.Workspace} is the
 * only way an agent touches code: paths stay inside the task's worktree and the only commands are the
 * project's named checks. {@link io.github.bhushanchinmay.botdesk.agent.CodingEngine} is the extension
 * point for plugging in a different agent.
 */
package io.github.bhushanchinmay.botdesk.agent;
