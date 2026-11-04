package io.github.bhushanchinmay.botdesk.web;

import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.domain.Records.Artifact;
import io.github.bhushanchinmay.botdesk.domain.Records.ChatMessage;
import io.github.bhushanchinmay.botdesk.domain.Records.Event;
import io.github.bhushanchinmay.botdesk.domain.Records.HumanRequest;
import io.github.bhushanchinmay.botdesk.domain.Records.ModelCall;
import io.github.bhushanchinmay.botdesk.domain.Task;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.project.Playbook;
import io.github.bhushanchinmay.botdesk.project.Project;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Server-rendered HTML. htmx refreshes the board every few seconds; no JavaScript framework. */
final class Views {

    private static final List<String> COLUMNS = List.of("Will be done", "In progress", "Done");

    private final Config config;
    private final Store store;
    private final Registry registry;
    private final DateTimeFormatter time;

    Views(Config config, Store store, Registry registry) {
        this.config = config;
        this.store = store;
        this.registry = registry;
        this.time = DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(config.zone());
    }

    // ---------- layout ----------

    String page(String title, String body) {
        return """
                <!doctype html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>%s · botdesk</title>
                <script src="/static/htmx.min.js"></script>
                <style>%s</style></head>
                <body hx-headers='{"X-Botdesk": "1"}' hx-on::response-error="alert(event.detail.xhr.responseText)">
                <header><b>botdesk</b>
                  <nav><a href="/">Board</a><a href="/chat">Chat</a></nav>
                  <span class="spend" hx-get="/fragments/spend" hx-trigger="load, every 10s">%s</span>
                </header>
                <main>%s</main></body></html>
                """.formatted(esc(title), CSS, spend(), body);
    }

    String spend() {
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return String.format("Today $%.2f · Month $%.2f of $%.2f",
                store.spendSince(dayStart), store.spendSince(monthStart), config.monthlyBudgetUsd());
    }

    // ---------- board ----------

    String home() {
        StringBuilder projectOptions = new StringBuilder();
        for (Project p : registry.projects()) {
            projectOptions.append("<option>").append(esc(p.name())).append("</option>");
        }
        StringBuilder kindOptions = new StringBuilder();
        for (Playbook pb : registry.playbooks()) {
            kindOptions.append("<option value=\"").append(esc(pb.kind())).append("\">")
                    .append(esc(pb.kind())).append(": ").append(esc(pb.description())).append("</option>");
        }
        String form = """
                <details class="new"><summary>+ New task</summary>
                <form hx-post="/tasks">
                  <label>Project <select name="project">%s</select></label>
                  <label>Kind of work <select name="kind">%s</select></label>
                  <label>Title <input name="title" required maxlength="120"></label>
                  <label>Details <textarea name="goal" rows="5"
                    placeholder="What you want, like a GitHub issue. The lead will plan it and ask you before any code is written."></textarea></label>
                  <button>Create</button>
                </form></details>
                """.formatted(projectOptions, kindOptions);
        // Do not refresh while you are typing inside the board.
        String board = "<div id=\"board\" hx-get=\"/fragments/board\" "
                + "hx-trigger=\"load, every 5s [!document.activeElement.closest('#board')]\">" + board() + "</div>";
        return page("Board", form + board);
    }

    String board() {
        StringBuilder sb = new StringBuilder();
        List<HumanRequest> open = store.openRequests();
        sb.append("<section class=\"needs\"><h2>Needs you (").append(open.size()).append(")</h2>");
        if (open.isEmpty()) sb.append("<p class=\"muted\">Nothing waiting for you.</p>");
        for (HumanRequest r : open) sb.append(requestCard(r));
        sb.append("</section>");

        List<Task> tasks = store.tasks();
        Instant weekAgo = Instant.now().minus(Duration.ofDays(7));
        sb.append("<section class=\"cols\">");
        for (String column : COLUMNS) {
            List<Task> inColumn = tasks.stream()
                    .filter(t -> t.state().column().equals(column))
                    .filter(t -> !t.state().isTerminal() || t.updatedAt().isAfter(weekAgo))
                    .sorted(Comparator.comparing(Task::updatedAt).reversed()).toList();
            sb.append("<div class=\"col\"><h3>").append(column).append(" (").append(inColumn.size()).append(")</h3>");
            inColumn.forEach(t -> sb.append(taskCard(t)));
            sb.append("</div>");
        }
        sb.append("</section>");

        sb.append("<section class=\"muted small\">Team: ");
        for (String role : List.of("lead", "engineer", "reviewer")) {
            if (config.roles().containsKey(role)) sb.append(role).append(" → ").append(esc(config.role(role).model())).append(" · ");
        }
        sb.append("Projects: ").append(esc(String.join(", ", registry.projects().stream().map(Project::name).toList())));
        sb.append("</section>");
        return sb.toString();
    }

    private String taskCard(Task t) {
        String score = t.reviewScore() < 0 ? "" : " · review " + t.reviewScore() + "/10";
        return """
                <a class="card s-%s" href="/tasks/%d"><b>#%d %s</b>
                <span>%s · %s · %s</span><span class="muted small">v%d · repairs %d%s · $%.3f · %s</span></a>
                """.formatted(t.state().name().toLowerCase(), t.id(), t.id(), esc(t.title()), esc(t.project()),
                esc(t.kind()), t.state(), t.outputVersion(), t.repairCycles(), score, store.taskCost(t.id()),
                time.format(t.updatedAt()));
    }

    private String requestCard(HumanRequest r) {
        String title = store.task(r.taskId()).map(Task::title).orElse("?");
        return """
                <div class="req"><div><b>%s</b> · <a href="/tasks/%d">#%d %s</a>
                <span class="muted small"> · request %d · %s</span></div>
                <pre>%s</pre>
                <form hx-post="/requests/%d">
                  <input name="text" placeholder="Reply or reason (optional for approve)">
                  <button name="decision" value="APPROVE">Approve</button>
                  <button name="decision" value="REPLY">Reply</button>
                  <button name="decision" value="REJECT" class="danger">Reject</button>
                </form></div>
                """.formatted(r.kind(), r.taskId(), r.taskId(), esc(title), r.id(), time.format(r.createdAt()),
                esc(r.prompt()), r.id());
    }

    // ---------- task detail ----------

    String task(Task t) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p><a href=\"/\">← Board</a></p><h1>#").append(t.id()).append(" ").append(esc(t.title())).append("</h1>");
        String duration = t.startedAt() == null ? "not started"
                : Duration.between(t.startedAt(), t.finishedAt() == null ? Instant.now() : t.finishedAt()).toMinutes() + " min";
        String pr = t.prUrl() == null ? "-" : "<a href=\"" + esc(t.prUrl()) + "\">" + esc(t.prUrl()) + "</a>";
        sb.append("""
                <table class="kv">
                <tr><th>State</th><td>%s</td><th>Project · kind</th><td>%s · %s</td></tr>
                <tr><th>Branch</th><td>%s</td><th>Pull request</th><td>%s</td></tr>
                <tr><th>Base → head</th><td>%s → %s</td><th>Approved commit</th><td>%s</td></tr>
                <tr><th>Version</th><td>v%d</td><th>Review score</th><td>%s</td></tr>
                <tr><th>Repair cycles</th><td>%d of %d</td><th>Your interventions</th><td>%d</td></tr>
                <tr><th>Cost</th><td>$%.4f</td><th>Elapsed</th><td>%s</td></tr>
                </table>
                """.formatted(t.state(), esc(t.project()), esc(t.kind()), esc(orDash(t.branch())), pr,
                sha(t.baseCommit()), sha(t.headCommit()), sha(t.approvedCommit()), t.outputVersion(),
                t.reviewScore() < 0 ? "-" : t.reviewScore() + "/10", t.repairCycles(), config.maxRepairCycles(),
                t.interventions(), store.taskCost(t.id()), duration));

        store.requests(t.id()).stream().filter(HumanRequest::open).forEach(r -> sb.append(requestCard(r)));

        sb.append("<h2>Contract</h2><h4>Request</h4><pre>").append(esc(t.goal())).append("</pre>");
        if (t.plan() != null) sb.append("<h4>Lead's plan</h4><pre>").append(esc(t.plan())).append("</pre>");
        if (t.acceptance() != null) sb.append("<h4>Done means</h4><pre>").append(esc(t.acceptance())).append("</pre>");
        if (t.lastFeedback() != null) sb.append("<h4>Latest feedback</h4><pre>").append(esc(t.lastFeedback())).append("</pre>");

        store.latestArtifact(t.id()).ifPresent(a -> sb.append(artifact(a)));

        sb.append("<h2>Timeline</h2><table class=\"log\">");
        for (Event e : store.events(t.id())) {
            sb.append("<tr><td>").append(time.format(e.at())).append("</td><td>").append(esc(e.actor()))
                    .append("</td><td>").append(esc(e.kind())).append("</td><td>").append(esc(e.detail())).append("</td></tr>");
        }
        sb.append("</table><h2>Model calls</h2><table class=\"log\">");
        for (ModelCall c : store.calls(t.id())) {
            sb.append(String.format("<tr><td>%s</td><td>%s</td><td>%s / %s</td><td>%d in · %d out</td><td>$%.4f</td></tr>",
                    time.format(c.at()), esc(c.role()), esc(c.provider()), esc(c.model()),
                    c.inputTokens(), c.outputTokens(), c.costUsd()));
        }
        sb.append("</table>");
        return page("#" + t.id(), sb.toString());
    }

    @SuppressWarnings("unchecked")
    private String artifact(Artifact a) {
        Map<String, Object> out;
        try {
            out = Json.parseObject(a.content());
        } catch (IllegalArgumentException e) {
            return "<h2>Latest output v" + a.version() + "</h2><pre>" + esc(a.content()) + "</pre>";
        }
        StringBuilder sb = new StringBuilder("<h2>Latest output v" + a.version() + " <span class=\"muted small\">commit "
                + sha(Obj.str(out, "commit", "")) + "</span></h2>");
        sb.append("<h4>Engineer's summary</h4><pre>").append(esc(Obj.str(out, "summary", ""))).append("</pre>");
        sb.append("<h4>Checks (re-run by botdesk)</h4><table class=\"log\">");
        for (Object o : Obj.list(out, "checks")) {
            Map<String, Object> c = (Map<String, Object>) o;
            boolean ok = Obj.bool(c, "passed", false);
            sb.append("<tr><td>").append(esc(Obj.str(c, "name", ""))).append("</td><td class=\"")
                    .append(ok ? "ok\">passed" : "bad\">failed (exit " + Obj.num(c, "exitCode", -1) + ")")
                    .append("</td><td><details><summary>output</summary><pre>").append(esc(Obj.str(c, "output", "")))
                    .append("</pre></details></td></tr>");
        }
        sb.append("</table><h4>Changed files</h4><pre>").append(esc(String.join("\n", Obj.strings(out, "changes")))).append("</pre>");
        sb.append("<details open><summary><b>Diff</b></summary><pre class=\"out diff\">").append(diffHtml(Obj.str(out, "diff", "")))
                .append("</pre></details>");
        return sb.toString();
    }

    private static String diffHtml(String diff) {
        StringBuilder sb = new StringBuilder();
        for (String line : diff.split("\n")) {
            String cls = line.startsWith("+") && !line.startsWith("+++") ? "add"
                    : line.startsWith("-") && !line.startsWith("---") ? "del" : line.startsWith("@@") ? "hunk" : "";
            sb.append(cls.isEmpty() ? esc(line) : "<span class=\"" + cls + "\">" + esc(line) + "</span>").append("\n");
        }
        return sb.toString();
    }

    private static String sha(String s) { return s == null || s.isEmpty() ? "-" : s.substring(0, Math.min(10, s.length())); }

    private static String orDash(String s) { return s == null ? "-" : s; }

    // ---------- chat ----------

    String chat() {
        String body = """
                <h1>Chat with the leader</h1>
                <div id="chatlog">%s</div>
                <form hx-post="/chat" hx-target="#chatlog" hx-indicator="#thinking"
                      hx-on::after-request="this.reset()">
                  <textarea name="text" rows="3" placeholder="Ask about projects and tasks, or ask for work. Commands: status, approve 12, reply 12 text"></textarea>
                  <button>Send</button> <span id="thinking" class="htmx-indicator muted">leader is thinking...</span>
                </form>
                """.formatted(chatLog());
        return page("Chat", body);
    }

    String chatLog() {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : store.recentChat(50)) {
            sb.append("<div class=\"msg ").append(m.from().equals("you") ? "me" : "bot").append("\"><span class=\"muted small\">")
                    .append(esc(m.from())).append(" · ").append(esc(m.channel())).append(" · ").append(time.format(m.at()))
                    .append("</span><pre>").append(esc(m.text())).append("</pre></div>");
        }
        return sb.toString();
    }

    String notFound() { return page("Not found", "<p>Not found. <a href=\"/\">Back</a></p>"); }

    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '&' -> sb.append("&amp;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static final String CSS = """
            body{font:14px/1.45 system-ui,sans-serif;margin:0;background:#f6f6f4;color:#222}
            header{display:flex;gap:16px;align-items:center;padding:10px 20px;background:#1f2937;color:#fff}
            header a{color:#cbd5e1;margin-right:12px;text-decoration:none} .spend{margin-left:auto;font-size:13px}
            main{padding:16px 20px;max-width:1200px;margin:auto} h1{font-size:20px} h2{font-size:16px;margin-top:22px}
            .cols{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:12px}
            .col{background:#ecebe7;border-radius:8px;padding:8px} .col h3{font-size:13px;margin:4px 4px 8px}
            .card{display:flex;flex-direction:column;gap:2px;background:#fff;border-radius:6px;padding:8px;margin-bottom:8px;
              text-decoration:none;color:inherit;border-left:4px solid #94a3b8}
            .s-running,.s-in_review,.s-revising{border-left-color:#2563eb} .s-done{border-left-color:#16a34a}
            .s-cancelled{border-left-color:#9ca3af;opacity:.7} .s-queued,.s-backlog{border-left-color:#a16207}
            .needs{background:#fff4f2;border:1px solid #f3c4bb;border-radius:8px;padding:8px 12px}
            .req{background:#fff;border-radius:6px;padding:8px;margin:8px 0}
            .req form,.new form{display:flex;gap:6px;flex-wrap:wrap;align-items:center}
            .new form{flex-direction:column;align-items:stretch;max-width:640px}
            input,select,textarea{font:inherit;padding:5px;border:1px solid #ccc;border-radius:4px;width:100%;box-sizing:border-box}
            .req input{flex:1;min-width:200px;width:auto}
            button{font:inherit;padding:5px 12px;border:0;border-radius:4px;background:#1f2937;color:#fff;cursor:pointer}
            button.danger{background:#b91c1c} pre{white-space:pre-wrap;background:#f3f3f1;padding:8px;border-radius:4px;margin:4px 0}
            pre.out{max-height:480px;overflow:auto} .muted{color:#6b7280} .small{font-size:12px}
            table{border-collapse:collapse;width:100%} td,th{padding:4px 6px;border-bottom:1px solid #e5e5e5;text-align:left;vertical-align:top}
            .kv th{width:18%;color:#6b7280;font-weight:normal} .msg.me pre{background:#e0ecff} .htmx-indicator{display:none}
            .diff .add{color:#166534;background:#dcfce7}.diff .del{color:#991b1b;background:#fee2e2}.diff .hunk{color:#6b21a8}
            .log td:first-child{white-space:nowrap} .ok{color:#166534}.bad{color:#b91c1c} .s-needs_you,.s-releasing{border-left-color:#7c3aed}
            .htmx-request .htmx-indicator,.htmx-request.htmx-indicator{display:inline}
            @media(max-width:800px){.cols{grid-template-columns:1fr}}
            """;
}
