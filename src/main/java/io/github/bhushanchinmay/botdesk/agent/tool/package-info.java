/**
 * Functions the engineer model may call. Add a tool by implementing {@link
 * io.github.bhushanchinmay.botdesk.agent.tool.Tool} and registering it with {@link
 * io.github.bhushanchinmay.botdesk.agent.tool.ToolRegistry#with}. Tools act only through the
 * Workspace, so they cannot widen the sandbox.
 */
package io.github.bhushanchinmay.botdesk.agent.tool;
