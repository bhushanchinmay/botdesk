package io.github.bhushanchinmay.botdesk.llm;

import io.github.bhushanchinmay.botdesk.config.Config;
import io.github.bhushanchinmay.botdesk.domain.Records.ModelCall;
import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;
import io.github.bhushanchinmay.botdesk.store.Store;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Talks to any provider that speaks the OpenAI /chat/completions format.
 * One class covers OpenRouter, Experiential Labs, Command Code and most others,
 * including tool calling (the model asks us to run a function and gets the result).
 *
 * Section map:
 *  1. Budget guard: refuse the call before spending money.
 *  2. Build the request body from role + provider config.
 *  3. Send with retries on rate limits and server errors.
 *  4. Parse text, tokens and cost; record the call in the store.
 */
public final class OpenAiCompatibleClient implements LlmClient {

    private static final int MAX_ATTEMPTS = 3;

    private final Config config;
    private final Store store;
    private final HttpClient http;
    private final UnaryOperator<String> env;
    private final Duration retryBackoff;

    public OpenAiCompatibleClient(Config config, Store store) {
        this(config, store, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
                System::getenv, Duration.ofSeconds(2));
    }

    /** Test constructor: inject env lookup and a zero backoff. */
    public OpenAiCompatibleClient(Config config, Store store, HttpClient http,
                                  UnaryOperator<String> env, Duration retryBackoff) {
        this.config = config;
        this.store = store;
        this.http = http;
        this.env = env;
        this.retryBackoff = retryBackoff;
    }

    @Override
    public Result complete(Request request) {
        Config.Role role = config.role(request.role());
        Config.Provider provider = config.provider(role.provider());

        // 1. Budget guard
        checkBudget(request.taskId());

        // 2. Request body
        String apiKey = env.apply(provider.apiKeyEnv());
        if (apiKey == null || apiKey.isBlank()) {
            throw new LlmException("Environment variable " + provider.apiKeyEnv() + " is not set for provider " + provider.name());
        }
        String body = Json.write(buildBody(role, provider, request));

        // 3. Send with retries
        String responseBody = send(provider, apiKey, body);

        // 4. Parse and record
        Result result = parse(responseBody, role, provider);
        store.recordCall(new ModelCall(request.taskId(), role.name(), provider.name(), result.model(),
                result.inputTokens(), result.outputTokens(), result.costUsd(), Instant.now()));
        return result;
    }

    private void checkBudget(long taskId) {
        Instant monthStart = ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate()
                .atStartOfDay(ZoneOffset.UTC).toInstant();
        double month = store.spendSince(monthStart);
        if (month >= config.monthlyBudgetUsd()) {
            throw new BudgetExceededException(String.format("Monthly budget reached: $%.2f of $%.2f",
                    month, config.monthlyBudgetUsd()));
        }
        if (taskId > 0) {
            double task = store.taskCost(taskId);
            if (task >= config.taskBudgetUsd()) {
                throw new BudgetExceededException(String.format("Task budget reached: $%.2f of $%.2f",
                        task, config.taskBudgetUsd()));
            }
        }
    }

    private Map<String, Object> buildBody(Config.Role role, Config.Provider provider, Request request) {
        Map<String, Object> body = new LinkedHashMap<>(provider.extraBody());
        body.put("model", role.model());
        body.put("messages", request.messages());
        if (!request.tools().isEmpty()) body.put("tools", request.tools());
        return body;
    }

    private String send(Config.Provider provider, String apiKey, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(provider.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        provider.headers().forEach(builder::header);
        HttpRequest httpRequest = builder.build();

        LlmException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status == 200) return response.body();
                last = new LlmException(provider.name() + " returned HTTP " + status + ": " + truncate(response.body()));
                boolean retryable = status == 429 || status >= 500;
                if (!retryable) throw last;
            } catch (IOException e) {
                last = new LlmException(provider.name() + " request failed: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted", e);
            }
            sleep(retryBackoff.multipliedBy(attempt));
        }
        throw last;
    }

    @SuppressWarnings("unchecked")
    private Result parse(String responseBody, Config.Role role, Config.Provider provider) {
        Map<String, Object> json = Json.parseObject(responseBody);
        List<Object> choices = Obj.list(json, "choices");
        if (choices.isEmpty()) throw new LlmException("No choices in response: " + truncate(responseBody));
        Map<String, Object> message = Obj.map((Map<String, Object>) choices.get(0), "message");
        String text = Obj.str(message, "content", "");

        List<ToolCall> toolCalls = new ArrayList<>();
        for (Object o : Obj.list(message, "tool_calls")) {
            Map<String, Object> call = (Map<String, Object>) o;
            Map<String, Object> fn = Obj.map(call, "function");
            toolCalls.add(new ToolCall(Obj.str(call, "id", "call_" + toolCalls.size()),
                    Obj.str(fn, "name", ""), Obj.str(fn, "arguments", "{}")));
        }
        // Echo the reply back in wire format on the next turn. Some providers reject null content.
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", text);
        if (!toolCalls.isEmpty()) assistant.put("tool_calls", Obj.list(message, "tool_calls"));

        Map<String, Object> usage = Obj.map(json, "usage");
        long in = Obj.num(usage, "prompt_tokens", 0);
        long out = Obj.num(usage, "completion_tokens", 0);
        // Prefer the provider's own cost figure (OpenRouter reports usage.cost); else estimate from config prices.
        double cost = usage.containsKey("cost")
                ? Obj.dbl(usage, "cost", 0)
                : (in * role.inputUsdPerM() + out * role.outputUsdPerM()) / 1_000_000.0;
        return new Result(text, toolCalls, assistant, provider.name(), Obj.str(json, "model", role.model()), in, out, cost);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
