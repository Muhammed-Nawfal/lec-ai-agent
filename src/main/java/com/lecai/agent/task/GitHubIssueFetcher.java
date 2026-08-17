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
        URI uri = URI.create("https://api.github.com/repos/" + owner + "/" + repo
                + "/issues?state=open&per_page=" + MAX_PER_PAGE);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[GitHubIssueFetcher] Unexpected status " + response.statusCode()
                        + " fetching issues for " + owner + "/" + repo + ", proceeding with 0 GitHub tasks.");
                return List.of();
            }
            List<Task> issues = parseIssues(response.body());
            return issues.size() > limit ? issues.subList(0, limit) : issues;
        } catch (IOException | InterruptedException e) {
            System.err.println("[GitHubIssueFetcher] Failed to fetch issues for " + owner + "/" + repo
                    + " (" + e.getMessage() + "), proceeding with 0 GitHub tasks.");
            return List.of();
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