package io.github.bhushanchinmay.botdesk.project;

import io.github.bhushanchinmay.botdesk.json.Json;
import io.github.bhushanchinmay.botdesk.json.Obj;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Opens draft pull requests through the GitHub REST API. Never merges: a human does that. */
public final class GitHub {

    private final String token;
    private final String apiBase;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public GitHub(String token) { this(token, "https://api.github.com"); }

    public GitHub(String token, String apiBase) {
        this.token = token;
        this.apiBase = apiBase;
    }

    public boolean configured() { return token != null && !token.isBlank(); }

    /** Returns the PR's web URL. */
    public String openDraftPullRequest(String repo, String head, String base, String title, String body) {
        String payload = Json.write(Map.of("title", title, "head", head, "base", base, "body", body, "draft", true));
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/repos/" + repo + "/pulls"))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 201) {
                throw new IllegalStateException("GitHub PR failed: HTTP " + res.statusCode() + " " + res.body());
            }
            return Obj.str(Json.parseObject(res.body()), "html_url", "");
        } catch (IOException e) {
            throw new IllegalStateException("GitHub PR failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", e);
        }
    }
}
