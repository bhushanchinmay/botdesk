package io.github.bhushanchinmay.botdesk.slack;

import io.github.bhushanchinmay.botdesk.chat.ChatService;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.orchestrator.Notifier;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Slack in Socket Mode: the app opens an outgoing WebSocket to Slack, so the VPS needs
 * no public URL and no open port. Uses only the JDK's built-in WebSocket client.
 *
 * Section map:
 *  1. connect(): ask Slack for a WebSocket URL (apps.connections.open) and open it.
 *  2. Listener: ack every envelope at once, then hand DMs and @mentions to ChatService.
 *  3. post(): send replies and "needs you" alerts with chat.postMessage.
 */
public final class SlackGateway implements Notifier {

    private static final Logger LOG = Logger.getLogger(SlackGateway.class.getName());
    private static final String API = "https://slack.com/api/";

    private final String appToken;
    private final String botToken;
    private final String notifyChannel;
    private final ChatService chat;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ScheduledExecutorService reconnects = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean reconnectPending = new AtomicBoolean(false);

    public SlackGateway(String appToken, String botToken, String notifyChannel, ChatService chat) {
        this.appToken = appToken;
        this.botToken = botToken;
        this.notifyChannel = notifyChannel;
        this.chat = chat;
    }

    // ---------- 1. connect ----------

    public void connect() {
        reconnectPending.set(false);
        try {
            HttpRequest open = HttpRequest.newBuilder(URI.create(API + "apps.connections.open"))
                    .header("Authorization", "Bearer " + appToken)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            Map<String, Object> res = Json.parseObject(http.send(open, HttpResponse.BodyHandlers.ofString()).body());
            if (!Obj.bool(res, "ok", false)) throw new IllegalStateException("Slack: " + Obj.str(res, "error", "unknown"));
            http.newWebSocketBuilder().buildAsync(URI.create(Obj.str(res, "url", "")), new Listener()).join();
            LOG.info("Slack connected");
        } catch (Exception e) {
            LOG.warning("Slack connect failed, retrying in 10s: " + e.getMessage());
            scheduleReconnect(10);
        }
    }

    /** onClose and onError can both fire for one drop; reconnect only once. */
    private void scheduleReconnect(int seconds) {
        if (reconnectPending.compareAndSet(false, true)) {
            reconnects.schedule(this::connect, seconds, TimeUnit.SECONDS);
        }
    }

    // ---------- 2. listener ----------

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                String frame = buffer.toString();
                buffer.setLength(0);
                handleFrame(ws, frame);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int status, String reason) {
            LOG.info("Slack socket closed (" + status + "), reconnecting");
            scheduleReconnect(2);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            LOG.warning("Slack socket error: " + error.getMessage());
            scheduleReconnect(5);
        }
    }

    private void handleFrame(WebSocket ws, String frame) {
        Map<String, Object> envelope = Json.parseObject(frame);
        String type = Obj.str(envelope, "type", "");
        if ("disconnect".equals(type)) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "refresh");
            return;
        }
        String envelopeId = Obj.str(envelope, "envelope_id", null);
        if (envelopeId != null) ws.sendText(Json.write(Map.of("envelope_id", envelopeId)), true); // ack within 3s
        if (!"events_api".equals(type)) return;

        Map<String, Object> event = Obj.map(Obj.map(envelope, "payload"), "event");
        String eventType = Obj.str(event, "type", "");
        boolean fromBot = event.containsKey("bot_id") || event.containsKey("subtype");
        boolean isDm = "message".equals(eventType) && "im".equals(Obj.str(event, "channel_type", ""));
        if (fromBot || !(isDm || "app_mention".equals(eventType))) return;

        String text = Obj.str(event, "text", "").replaceAll("<@[A-Z0-9]+>", "").trim();
        String channel = Obj.str(event, "channel", "");
        String threadTs = isDm ? null : Obj.str(event, "ts", null);
        // LLM calls can take a while; never block the socket thread.
        Thread.ofVirtual().start(() -> post(channel, chat.handle(text, "slack"), threadTs));
    }

    // ---------- 3. post ----------

    @Override
    public void notify(String text) {
        if (notifyChannel != null && !notifyChannel.isBlank()) post(notifyChannel, text, null);
    }

    private void post(String channel, String text, String threadTs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("text", text);
        if (threadTs != null) body.put("thread_ts", threadTs);
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + "chat.postMessage"))
                .header("Authorization", "Bearer " + botToken)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build();
        http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    Map<String, Object> json = Json.parseObject(res.body());
                    if (!Obj.bool(json, "ok", false)) LOG.warning("Slack post failed: " + Obj.str(json, "error", "?"));
                });
    }
}
