package io.github.bhushanchinmay.botdesk.orchestrator;

import io.github.bhushanchinmay.botdesk.agent.CodingEngine;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.quality.QualityGates;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.util.Objects;

/**
 * Everything a pipeline stage may depend on, in one place. Built once in Main (or a test) and
 * shared by all stages. Each dependency is an interface or a small class, so a test can swap any of them.
 */
public record PipelineServices(Config config, Store store, Registry registry, TaskService tasks, LlmClient llm,
                               CodingEngine engine, Git git, GitHub github, QualityGates gates) {

    public PipelineServices {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(llm, "llm");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(git, "git");
        Objects.requireNonNull(github, "github");
        Objects.requireNonNull(gates, "gates");
    }
}
