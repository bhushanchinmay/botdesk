/**
 * Moves tasks through the pipeline. {@link io.github.bhushanchinmay.botdesk.orchestrator.TaskService}
 * is the only place task state changes; {@link
 * io.github.bhushanchinmay.botdesk.orchestrator.Orchestrator} picks the next task and runs its stage.
 */
package io.github.bhushanchinmay.botdesk.orchestrator;
