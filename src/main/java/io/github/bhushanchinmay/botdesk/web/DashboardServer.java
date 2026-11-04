package io.github.bhushanchinmay.botdesk.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bhushanchinmay.botdesk.project.Registry;
import io.github.bhushanchinmay.botdesk.chat.ChatService;
import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService;
import io.github.bhushanchinmay.botdesk.orchestrator.TaskService.Decision;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The dashboard, on the JDK's built-in HTTP server.
 *
 * Security model: binds to 127.0.0.1 by default, so it is reachable only through an SSH
 * tunnel or Tailscale. Every POST must carry the X-Botdesk header (htmx adds it). A page on
 * another site cannot add custom headers to a cross-site request, which blocks CSRF.
 */
public final class DashboardServer {

    private static final Logger LOG = Logger.getLogger(DashboardServer.class.getName());

    private final Config config;
    private final Store store;
    private final TaskService tasks;
    private final ChatService chat;
    private final Views views;
    private HttpServer server;

    public DashboardServer(Config config, Store store, Registry registry, TaskService tasks, ChatService chat) {
        this.config = config;
        this.store = store;
        this.tasks = tasks;
        this.chat = chat;
        this.views = new Views(config, store, registry);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.bind(), config.port()), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::route);
        server.start();
        LOG.info("Dashboard on http://" + config.bind() + ":" + config.port());
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    public int port() { return server.getAddress().getPort(); }

    private void route(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            if (method.equals("POST") && !"1".equals(ex.getRequestHeaders().getFirst("X-Botdesk"))) {
                send(ex, 403, "text/plain", "Missing X-Botdesk header");
                return;
            }
            switch (method + " " + path) {
                case "GET /" -> html(ex, views.home());
                case "GET /fragments/board" -> html(ex, views.board());
                case "GET /fragments/spend" -> html(ex, views.spend());
                case "GET /chat" -> html(ex, views.chat());
                case "GET /static/htmx.min.js" -> staticFile(ex, "/static/htmx.min.js", "application/javascript");
                case "GET /health" -> send(ex, 200, "text/plain", "ok");
                case "GET /favicon.ico" -> { ex.sendResponseHeaders(204, -1); ex.close(); }
                case "POST /tasks" -> createTask(ex);
                case "POST /chat" -> postChat(ex);
                default -> {
                    if (method.equals("GET") && path.startsWith("/tasks/")) taskPage(ex, path);
                    else if (method.equals("POST") && path.startsWith("/requests/")) answer(ex, path);
                    else send(ex, 404, "text/html; charset=utf-8", views.notFound());
                }
            }
        } catch (IllegalArgumentException e) {
            send(ex, 400, "text/plain; charset=utf-8", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warning("Request failed: " + e);
            send(ex, 500, "text/plain; charset=utf-8", "Error: " + e.getMessage());
        }
    }

    private void taskPage(HttpExchange ex, String path) throws IOException {
        long id = parseId(path.substring("/tasks/".length()));
        var task = store.task(id);
        if (task.isEmpty()) send(ex, 404, "text/html; charset=utf-8", views.notFound());
        else html(ex, views.task(task.get()));
    }

    private void createTask(HttpExchange ex) throws IOException {
        Map<String, String> form = form(ex);
        String title = required(form, "title");
        tasks.create(required(form, "project"), required(form, "kind"), title, form.getOrDefault("goal", ""));
        refresh(ex);
    }

    private void answer(HttpExchange ex, String path) throws IOException {
        long id = parseId(path.substring("/requests/".length()));
        Map<String, String> form = form(ex);
        Decision decision = Decision.valueOf(required(form, "decision"));
        String message = tasks.answer(id, decision, form.getOrDefault("text", "").trim());
        if (message.startsWith("Done")) refresh(ex);
        else send(ex, 409, "text/plain; charset=utf-8", message);
    }

    private void postChat(HttpExchange ex) throws IOException {
        chat.handle(form(ex).getOrDefault("text", ""), "dashboard");
        html(ex, views.chatLog());
    }

    // ---------- helpers ----------

    /** Tells htmx to reload the page after a successful POST. */
    private static void refresh(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("HX-Refresh", "true");
        send(ex, 200, "text/plain", "ok");
    }

    private static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readNBytes(1_000_000), StandardCharsets.UTF_8);
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(key, value);
        }
        return out;
    }

    private static String required(Map<String, String> form, String key) {
        String v = form.get(key);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing field: " + key);
        return v.trim();
    }

    private static long parseId(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad id: " + s);
        }
    }

    private void staticFile(HttpExchange ex, String resource, String type) throws IOException {
        try (InputStream in = DashboardServer.class.getResourceAsStream(resource)) {
            if (in == null) {
                send(ex, 404, "text/plain", "missing " + resource);
                return;
            }
            ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
            sendBytes(ex, 200, type, in.readAllBytes());
        }
    }

    private static void html(HttpExchange ex, String body) throws IOException {
        send(ex, 200, "text/html; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        sendBytes(ex, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange ex, int status, String type, byte[] bytes) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }
}
