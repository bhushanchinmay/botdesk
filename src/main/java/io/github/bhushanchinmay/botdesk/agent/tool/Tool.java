package io.github.bhushanchinmay.botdesk.agent.tool;

import io.github.bhushanchinmay.botdesk.agent.Workspace;

import java.io.IOException;
import java.util.Map;

/**
 * A function the engineer model may call. Every tool acts only through the {@link Workspace},
 * which enforces the sandbox rules, so a new tool cannot widen what the agent can reach.
 *
 * <p>Return a short, plain-text result written for the model to read. Throw
 * {@link SecurityException} for refused actions; the agent turns it into "REFUSED: ..." for the model.
 */
public interface Tool {

    String name();

    /** Shown to the model. May depend on the workspace, for example to list the available checks. */
    String description(Workspace workspace);

    /** Parameter name to description. All parameters are required strings; order is kept. */
    Map<String, String> parameters();

    String execute(Map<String, String> args, Workspace workspace) throws IOException;
}
