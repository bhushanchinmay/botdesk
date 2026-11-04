package io.github.bhushanchinmay.botdesk.config;

import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Everything that changes between machines or experiments lives in botdesk.json.
 * Team members ask for a role ("lead", "engineer", "reviewer"); this file decides which
 * provider and model fill it. API keys are never in this file, only the names of the
 * environment variables that hold them.
 */
public record Config(
        String bind, int port, ZoneId zone,
        Path dataDir, Path projectsDir, Path playbooksDir, Path workspacesDir,
        int tickSeconds, int maxRepairCycles, int maxAgentSteps, int reviewPassScore, int checkTimeoutSeconds,
        double monthlyBudgetUsd, double taskBudgetUsd,
        Map<String, Provider> providers, Map<String, Role> roles,
        GitIdentity git, String githubTokenEnv,
        List<Routine> routines, Slack slack, List<String> allowedHosts) {

    /** Any OpenAI-compatible endpoint: OpenRouter, Experiential Labs, Command Code, etc. */
    public record Provider(String name, String baseUrl, String apiKeyEnv,
                           Map<String, String> headers, Map<String, Object> extraBody) {}

    /** Prices are per million tokens; used only when the provider does not report cost itself. */
    public record Role(String name, String provider, String model, double inputUsdPerM, double outputUsdPerM) {}

    /** The name and email on commits the team makes. */
    public record GitIdentity(String name, String email) {}

    public record Routine(String name, String project, String kind, String title, String goal,
                          int everyHours, boolean enabled) {}

    /** allowedUserIds: Slack member IDs allowed to command the team. Anyone else is ignored. */
    public record Slack(boolean enabled, String appTokenEnv, String botTokenEnv, String notifyChannel,
                        List<String> allowedUserIds) {}

    public static final int MIN_ROUTINE_HOURS = 1;

    // The keys each part of botdesk.json may contain. Add a key here when you add a setting.
    private static final Set<String> ROOT_KEYS = Set.of("server", "timezone", "dataDir", "projectsDir",
            "playbooksDir", "workspacesDir", "tickSeconds", "maxRepairCycles", "maxAgentSteps", "reviewPassScore",
            "checkTimeoutSeconds", "monthlyBudgetUsd", "taskBudgetUsd", "providers", "roles", "git",
            "githubTokenEnv", "routines", "slack");
    private static final Set<String> SERVER_KEYS = Set.of("bind", "port", "allowedHosts");
    private static final Set<String> PROVIDER_KEYS = Set.of("baseUrl", "apiKeyEnv", "headers", "extraBody");
    private static final Set<String> ROLE_KEYS = Set.of("provider", "model", "inputUsdPerM", "outputUsdPerM");
    private static final Set<String> GIT_KEYS = Set.of("name", "email");
    private static final Set<String> ROUTINE_KEYS = Set.of("name", "project", "kind", "title", "goal",
            "everyHours", "enabled");
    private static final Set<String> SLACK_KEYS = Set.of("enabled", "appTokenEnv", "botTokenEnv", "notifyChannel",
            "allowedUserIds");

    public Role role(String name) {
        Role r = roles.get(name);
        if (r == null) throw new IllegalArgumentException("No role '" + name + "' in botdesk.json");
        return r;
    }

    public Provider provider(String name) {
        Provider p = providers.get(name);
        if (p == null) throw new IllegalArgumentException("No provider '" + name + "' in botdesk.json");
        return p;
    }

    public static Config load(Path file) throws IOException {
        try {
            return parse(Json.parseObject(Files.readString(file)), file.toAbsolutePath().getParent());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(file.getFileName() + ": " + e.getMessage(), e);
        }
    }

    static Config parse(Map<String, Object> root, Path base) {
        Obj.requireKnownKeys(root, "the top level", ROOT_KEYS);

        Map<String, Provider> providers = new LinkedHashMap<>();
        Obj.map(root, "providers").forEach((name, v) -> {
            Map<String, Object> m = cast(v);
            Obj.requireKnownKeys(m, "providers." + name, PROVIDER_KEYS);
            Map<String, String> headers = new LinkedHashMap<>();
            Obj.map(m, "headers").forEach((k, hv) -> headers.put(k, String.valueOf(hv)));
            providers.put(name, new Provider(name, stripSlash(Obj.str(m, "baseUrl", "")),
                    Obj.str(m, "apiKeyEnv", ""), headers, Obj.map(m, "extraBody")));
        });

        Map<String, Role> roles = new LinkedHashMap<>();
        Obj.map(root, "roles").forEach((name, v) -> {
            Map<String, Object> m = cast(v);
            Obj.requireKnownKeys(m, "roles." + name, ROLE_KEYS);
            Role role = new Role(name, Obj.str(m, "provider", ""), Obj.str(m, "model", ""),
                    Obj.dblIn(m, "inputUsdPerM", 0, 0, 1000), Obj.dblIn(m, "outputUsdPerM", 0, 0, 1000));
            if (!providers.containsKey(role.provider())) {
                throw new IllegalArgumentException("Role '" + name + "' uses unknown provider '" + role.provider() + "'");
            }
            roles.put(name, role);
        });

        List<Routine> routines = Obj.list(root, "routines").stream().map(o -> {
            Map<String, Object> m = cast(o);
            Obj.requireKnownKeys(m, "routine '" + Obj.str(m, "name", "?") + "'", ROUTINE_KEYS);
            int hours = Obj.intIn(m, "everyHours", 24, MIN_ROUTINE_HOURS, 24 * 31);
            return new Routine(Obj.str(m, "name", ""), Obj.str(m, "project", ""), Obj.str(m, "kind", ""),
                    Obj.str(m, "title", ""), Obj.str(m, "goal", ""), hours, Obj.bool(m, "enabled", false));
        }).toList();

        Map<String, Object> s = Obj.map(root, "slack");
        Obj.requireKnownKeys(s, "slack", SLACK_KEYS);
        Slack slack = new Slack(Obj.bool(s, "enabled", false), Obj.str(s, "appTokenEnv", "SLACK_APP_TOKEN"),
                Obj.str(s, "botTokenEnv", "SLACK_BOT_TOKEN"), Obj.str(s, "notifyChannel", ""),
                Obj.strings(s, "allowedUserIds"));

        Map<String, Object> g = Obj.map(root, "git");
        Obj.requireKnownKeys(g, "git", GIT_KEYS);
        GitIdentity git = new GitIdentity(Obj.str(g, "name", "botdesk"), Obj.str(g, "email", "botdesk@localhost"));

        Map<String, Object> server = Obj.map(root, "server");
        Obj.requireKnownKeys(server, "server", SERVER_KEYS);
        return new Config(
                Obj.str(server, "bind", "127.0.0.1"), Obj.intIn(server, "port", 8080, 1, 65535),
                ZoneId.of(Obj.str(root, "timezone", ZoneId.systemDefault().getId())),
                base.resolve(Obj.str(root, "dataDir", "data")),
                base.resolve(Obj.str(root, "projectsDir", "projects")),
                base.resolve(Obj.str(root, "playbooksDir", "playbooks")),
                base.resolve(Obj.str(root, "workspacesDir", "data/workspaces")),
                Obj.intIn(root, "tickSeconds", 5, 1, 3600), Obj.intIn(root, "maxRepairCycles", 2, 0, 10),
                Obj.intIn(root, "maxAgentSteps", 30, 1, 200), Obj.intIn(root, "reviewPassScore", 8, 1, 10),
                Obj.intIn(root, "checkTimeoutSeconds", 600, 10, 7200),
                Obj.dblIn(root, "monthlyBudgetUsd", 10, 0, 10_000), Obj.dblIn(root, "taskBudgetUsd", 2, 0, 1_000),
                providers, roles, git, Obj.str(root, "githubTokenEnv", "GITHUB_TOKEN"), routines, slack,
                Obj.strings(server, "allowedHosts"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) { return (Map<String, Object>) o; }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
