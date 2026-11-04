package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.ToolLoopAgent;
import io.github.bhushanchinmay.botdesk.chat.ChatService;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.llm.OpenAiCompatibleClient;
import io.github.bhushanchinmay.botdesk.orchestrator.Orchestrator;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.slack.SlackGateway;
import io.github.bhushanchinmay.botdesk.store.Store;
import io.github.bhushanchinmay.botdesk.web.DashboardServer;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Wires the parts together. No framework: constructor injection by hand, so every
 * dependency is visible in one place.
 *
 * Usage: java -jar botdesk.jar [path/to/botdesk.json]
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        Config config = Config.load(Path.of(args.length > 0 ? args[0] : "botdesk.json"));

        Store store = new Store(config.dataDir());
        Registry registry = Registry.load(config.projectsDir(), config.playbooksDir());
        LlmClient llm = new OpenAiCompatibleClient(config, store);
        TaskService tasks = new TaskService(store, registry);
        Orchestrator orchestrator = new Orchestrator(config, store, registry, llm, tasks,
                new ToolLoopAgent(llm, store), new Git(config.git()), new GitHub(System.getenv(config.githubTokenEnv())));
        ChatService chat = new ChatService(store, registry, tasks, llm);

        if (config.slack().enabled()) {
            String appToken = System.getenv(config.slack().appTokenEnv());
            String botToken = System.getenv(config.slack().botTokenEnv());
            if (appToken == null || botToken == null) {
                throw new IllegalStateException("Slack is enabled but " + config.slack().appTokenEnv()
                        + " or " + config.slack().botTokenEnv() + " is not set");
            }
            SlackGateway slack = new SlackGateway(appToken, botToken, config.slack().notifyChannel(), chat);
            tasks.setNotifier(slack);
            slack.connect();
        }

        DashboardServer dashboard = new DashboardServer(config, store, registry, tasks, chat);
        dashboard.start();

        // One scheduler thread runs the orchestrator, so task steps never race each other.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                orchestrator.tick(10);
            } catch (RuntimeException e) {
                LOG.warning("Tick failed: " + e);
            }
        }, 2, config.tickSeconds(), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            scheduler.shutdown();
            dashboard.stop();
        }));
        LOG.info("botdesk started: " + registry.projects().size() + " project(s), "
                + registry.playbooks().size() + " kind(s) of work");
    }
}
