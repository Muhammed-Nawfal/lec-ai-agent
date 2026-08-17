package com.lecai.agent.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

// a service file that fetches issues from the open source github repo and filters out PRs and returns a list of Task objects
public class GitHubIssueFetcher {

    private static final String USER_AGENT = "lec-ai-agent";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final int MAX_PER_PAGE = 100;

    public List<Task> fetchIssues(String owner, String repo, int limit) {
        // GitHub's API occasionally 5xx's transiently (observed 504s in testing);
        // one retry after a short pause resolves most of those without giving up
        // GitHub tasks entirely and falling back to a synthetic-only run.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<Task> issues = attemptFetch(owner, repo);
                if (issues != null) {
                    return issues.size() > limit ? issues.subList(0, limit) : issues;
                }
            } catch (IOException | InterruptedException e) {
                System.err.println("[GitHubIssueFetcher] Attempt " + attempt + " failed for " + owner + "/" + repo
                        + " (" + e.getMessage() + ")");
            }
            if (attempt == 1) {
                sleepBeforeRetry();
            }
        }
        System.err.println("[GitHubIssueFetcher] Giving up after 2 attempts for " + owner + "/" + repo
                + ", proceeding with 0 GitHub tasks.");
        return List.of();
    }

    private List<Task> attemptFetch(String owner, String repo) throws IOException, InterruptedException {
        URI uri = URI.create("https://api.github.com/repos/" + owner + "/" + repo
                + "/issues?state=open&per_page=" + MAX_PER_PAGE);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("[GitHubIssueFetcher] Unexpected status " + response.statusCode()
                    + " fetching issues for " + owner + "/" + repo);
            return null;
        }
        return parseIssues(response.body());
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Task> parseIssues(String responseBody) throws IOException {
        List<Task> tasks = new ArrayList<>();
        JsonNode root = mapper.readTree(responseBody);
        for (JsonNode node : root) {
            if (node.has("pull_request")) {
                continue;
            }
            int number = node.path("number").asInt();
            String title = node.path("title").asText("");
            String body = node.hasNonNull("body") ? node.get("body").asText() : "";
            String url = node.path("html_url").asText("");
            tasks.add(new Task("gh-" + number, TaskSource.GITHUB, title, body, url));
        }
        return tasks;
    }
}