package io.github.bhushanchinmay.botdesk.domain;

import io.github.bhushanchinmay.botdesk.json.Obj;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small immutable records stored alongside tasks. */
public final class Records {

    private Records() {}

    /** One line in a task's timeline. actor: leader | worker | validator | you | system. */
    public record Event(long id, long taskId, Instant at, String actor, String kind, String detail) {
        public Map<String, Object> toMap() {
            return ordered("id", id, "taskId", taskId, "at", at.toString(), "actor", actor, "kind", kind, "detail", detail);
        }
        public static Event fromMap(Map<String, Object> m) {
            return new Event(Obj.num(m, "id", 0), Obj.num(m, "taskId", 0), Instant.parse(Obj.str(m, "at", "")),
                    Obj.str(m, "actor", ""), Obj.str(m, "kind", ""), Obj.str(m, "detail", ""));
        }
    }

    public enum RequestKind { CONFIRM_TASK, FINAL_APPROVAL, ESCALATION, ERROR, BUDGET }

    /**
     * Something only Chinmay can decide. outputVersion binds an approval to one exact output:
     * if the output changes after the request was raised, the approval is refused as stale.
     */
    public record HumanRequest(long id, long taskId, RequestKind kind, String prompt, int outputVersion,
                               boolean open, String decision, String answer, Instant createdAt) {
        public HumanRequest close(String decision, String answer) {
            return new HumanRequest(id, taskId, kind, prompt, outputVersion, false, decision, answer, createdAt);
        }
        public Map<String, Object> toMap() {
            return ordered("id", id, "taskId", taskId, "kind", kind.name(), "prompt", prompt,
                    "outputVersion", outputVersion, "open", open, "decision", decision, "answer", answer,
                    "createdAt", createdAt.toString());
        }
        public static HumanRequest fromMap(Map<String, Object> m) {
            return new HumanRequest(Obj.num(m, "id", 0), Obj.num(m, "taskId", 0),
                    RequestKind.valueOf(Obj.str(m, "kind", "ERROR")), Obj.str(m, "prompt", ""),
                    (int) Obj.num(m, "outputVersion", 0), Obj.bool(m, "open", true),
                    Obj.str(m, "decision", null), Obj.str(m, "answer", null),
                    Instant.parse(Obj.str(m, "createdAt", "")));
        }
    }

    /** One worker output. sha256 makes "which version was approved" checkable. */
    public record Artifact(long taskId, int version, String sha256, String content, Instant at) {
        public Map<String, Object> toMap() {
            return ordered("taskId", taskId, "version", version, "sha256", sha256, "content", content, "at", at.toString());
        }
        public static Artifact fromMap(Map<String, Object> m) {
            return new Artifact(Obj.num(m, "taskId", 0), (int) Obj.num(m, "version", 0), Obj.str(m, "sha256", ""),
                    Obj.str(m, "content", ""), Instant.parse(Obj.str(m, "at", "")));
        }
    }

    /** One LLM API call, for cost tracking. taskId 0 means chat. */
    public record ModelCall(long taskId, String role, String provider, String model,
                            long inputTokens, long outputTokens, double costUsd, Instant at) {
        public Map<String, Object> toMap() {
            return ordered("taskId", taskId, "role", role, "provider", provider, "model", model,
                    "inputTokens", inputTokens, "outputTokens", outputTokens, "costUsd", costUsd, "at", at.toString());
        }
        public static ModelCall fromMap(Map<String, Object> m) {
            return new ModelCall(Obj.num(m, "taskId", 0), Obj.str(m, "role", ""), Obj.str(m, "provider", ""),
                    Obj.str(m, "model", ""), Obj.num(m, "inputTokens", 0), Obj.num(m, "outputTokens", 0),
                    Obj.dbl(m, "costUsd", 0), Instant.parse(Obj.str(m, "at", "")));
        }
    }

    /** from: you | leader. channel: dashboard | slack. */
    public record ChatMessage(String from, String text, String channel, Instant at) {
        public Map<String, Object> toMap() {
            return ordered("from", from, "text", text, "channel", channel, "at", at.toString());
        }
        public static ChatMessage fromMap(Map<String, Object> m) {
            return new ChatMessage(Obj.str(m, "from", ""), Obj.str(m, "text", ""), Obj.str(m, "channel", ""),
                    Instant.parse(Obj.str(m, "at", "")));
        }
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
