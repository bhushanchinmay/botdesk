package io.github.bhushanchinmay.botdesk.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The only way the system talks to models. Callers name a role ("lead", "engineer", "reviewer"),
 * never a vendor, so swapping models is a config change, not a code change.
 * Messages and tools use the OpenAI chat format, which most providers accept.
 */
public interface LlmClient {

    Result complete(Request request);

    /** taskId 0 means "not tied to a task" (for example chat). tools may be empty. */
    record Request(long taskId, String role, List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        public Request(long taskId, String role, List<Map<String, Object>> messages) {
            this(taskId, role, messages, List.of());
        }
    }

    /** A function the model asked us to run. argumentsJson is a JSON object as a string. */
    record ToolCall(String id, String name, String argumentsJson) {}

    /**
     * assistantMessage is the model's reply in wire format, so the agent loop can append it
     * to the conversation unchanged before adding tool results.
     */
    record Result(String text, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                  String provider, String model, long inputTokens, long outputTokens, double costUsd) {}

    static Map<String, Object> system(String text) { return message("system", text); }

    static Map<String, Object> user(String text) { return message("user", text); }

    static Map<String, Object> assistant(String text) { return message("assistant", text); }

    static Map<String, Object> toolResult(String toolCallId, String content) {
        Map<String, Object> m = message("tool", content);
        m.put("tool_call_id", toolCallId);
        return m;
    }

    private static Map<String, Object> message(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /** Thrown before a call when it would break the monthly or per-task budget. */
    final class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) { super(message); }
    }

    final class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
