package com.lecai.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations.Schema;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

// another tool used by the agent to check a URL against the URLhaus database of known malware-distribution URLs
public class UrlhausCheckTool {

    private static final String ENDPOINT = "https://urlhaus-api.abuse.ch/v1/url/";
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Schema(description = "Checks a single URL against URLhaus, abuse.ch's free community database of "
            + "known malware-distribution URLs. Returns UNSAFE if the URL is listed (with the threat "
            + "type in the reason), SAFE if it was not found in the database, or NEEDS_VERIFICATION if "
            + "the check itself could not be completed (missing credentials, network failure, or an "
            + "inconclusive response) -- in that last case, the URL still has not actually been cleared.")
    public static RuleCheckResult checkUrlReputation(
            @Schema(name = "url", description = "The URL to look up in the URLhaus database.", optional = false)
            String url) {
        String authKey = authKey();
        if (authKey == null || authKey.isBlank()) {
            return new RuleCheckResult(Verdict.NEEDS_VERIFICATION, "urlhaus-unavailable",
                    "URLHAUS_AUTH_KEY is not configured; could not verify \"" + url + "\" against URLhaus.");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("Auth-Key", authKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "url=" + URLEncoder.encode(url, StandardCharsets.UTF_8)))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = MAPPER.readTree(response.body());
            String status = root.path("query_status").asText("");

            if (status.equals("ok")) {
                String threat = root.path("threat").asText("unknown");
                String urlStatus = root.path("url_status").asText("unknown");
                return new RuleCheckResult(Verdict.UNSAFE, "urlhaus-listed",
                        "URL is listed in URLhaus as a known malware-distribution URL "
                                + "(threat: " + threat + ", status: " + urlStatus + "): \"" + url + "\"");
            }
            if (status.equals("no_results")) {
                return new RuleCheckResult(Verdict.SAFE, "urlhaus-clean",
                        "URL was not found in the URLhaus malware URL database: \"" + url + "\"");
            }
            return new RuleCheckResult(Verdict.NEEDS_VERIFICATION, "urlhaus-inconclusive",
                    "URLhaus returned an inconclusive status (\"" + status + "\") for \"" + url + "\"");
        } catch (IOException | InterruptedException e) {
            return new RuleCheckResult(Verdict.NEEDS_VERIFICATION, "urlhaus-unavailable",
                    "Could not reach URLhaus to verify \"" + url + "\" (" + e.getMessage() + ")");
        }
    }

    private static String authKey() {
        String fromEnv = System.getenv("URLHAUS_AUTH_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return Dotenv.configure().ignoreIfMissing().load().get("URLHAUS_AUTH_KEY");
    }
}
