package io.github.bhushanchinmay.botdesk;

import io.github.bhushanchinmay.botdesk.agent.ToolLoopAgent;
import io.github.bhushanchinmay.botdesk.chat.ChatService;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.config.SetupCheck;
import io.github.bhushanchinmay.botdesk.llm.LlmClient;
import io.github.bhushanchinmay.botdesk.llm.OpenAiCompatibleClient;
import io.github.bhushanchinmay.botdesk.orchestrator.Orchestrator;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService;
import io.github.bhushanchinmay.botdesk.project.Git;
import io.github.bhushanchinmay.botdesk.project.GitHub;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.slack.SlackGateway;
import io.github.bhushanchinmay.botdesk.store.JsonFileStore;
import io.github.bhushanchinmay.botdesk.store.Store;
import io.github.bhushanchinmay.botdesk.web.DashboardServer;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Wires the parts together. No framework: constructor injection by hand, so every
 * dependency is visible in one place.
 *
 * Usage: java -jar botdesk.jar [path/to/botdesk.json] [--check]
 */
public final class Main {

    private static final Logger LOG = Logger.getLogger(Main.class.getName());
    private static final int MAX_STEPS_PER_TICK = 10;
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    public static void main(String[] args) throws Exception {
        boolean checkOnly = List.of(args).contains("--check");
        String configPath = Arrays.stream(args).filter(a -> !a.startsWith("--")).findFirst().orElse("botdesk.json");
        // Load and validate every local file first, so --check also catches typos in projects and playbooks.
        Config config;
        Registry registry;
        try {
            config = Config.load(Path.of(configPath));
            registry = Registry.load(config.projectsDir(), config.playbooksDir());
            requireKnownRoutineTargets(config, registry);
        } catch (IllegalArgumentException | IllegalStateException e) {
            System.err.println("Config error: " + e.getMessage());
            System.exit(2);
            return;
        }

        // One client for the whole app: HttpClient is immutable and thread-safe, and it pools connections.
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

        // Check every role's key and model first. With --check, that is all we do.
        boolean ready = new SetupCheck(config, System::getenv, http, System.out).run();
        if (checkOnly) System.exit(ready ? 0 : 1);

        Store store = new JsonFileStore(config.dataDir());
        LlmClient llm = new OpenAiCompatibleClient(config, store, http);
        TaskService tasks = new TaskService(store, registry);
        Orchestrator orchestrator = new Orchestrator(config, store, registry, llm, tasks,
                new ToolLoopAgent(llm, store), new Git(config.git()), new GitHub(System.getenv(config.githubTokenEnv()), http));
        ChatService chat = new ChatService(store, registry, tasks, llm);

        if (config.slack().enabled()) {
            String appToken = System.getenv(config.slack().appTokenEnv());
            String botToken = System.getenv(config.slack().botTokenEnv());
            if (appToken == null || botToken == null) {
                throw new IllegalStateException("Slack is enabled but " + config.slack().appTokenEnv()
                        + " or " + config.slack().botTokenEnv() + " is not set");
            }
            SlackGateway slack = new SlackGateway(appToken, botToken, config.slack().notifyChannel(),
                    config.slack().allowedUserIds(), chat, http);
            tasks.setNotifier(slack);
            slack.connect();
        }

        DashboardServer dashboard = new DashboardServer(config, store, registry, tasks, chat);
        dashboard.start();

        // One named thread runs the orchestrator, so task steps never race each other.
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("botdesk-orchestrator").factory());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                orchestrator.tick(MAX_STEPS_PER_TICK);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Tick failed; the next tick retries", e);
            } catch (Error e) {
                // An exception escaping here would silently cancel every future tick. Make it loud.
                LOG.log(Level.SEVERE, "Orchestrator stopped by a fatal error", e);
                throw e;
            }
        }, 2, config.tickSeconds(), TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            // Let an in-flight step finish. If it takes too long, interrupt it: the stage then fails
            // cleanly into FAILED with a retry button, instead of being left in RUNNING.
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    scheduler.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            dashboard.stop();
        }, "botdesk-shutdown"));
        LOG.info("botdesk started: " + registry.projects().size() + " project(s), "
                + registry.playbooks().size() + " kind(s) of work");
    }

    /** A routine with a typo in its project or kind would otherwise fail quietly once a day. */
    private static void requireKnownRoutineTargets(Config config, Registry registry) {
        for (Config.Routine r : config.routines()) {
            if (r.enabled() && (registry.project(r.project()).isEmpty() || registry.playbook(r.kind()).isEmpty())) {
                throw new IllegalStateException("Routine '" + r.name() + "' names an unknown project or kind: "
                        + r.project() + " / " + r.kind());
            }
        }
    }
}
