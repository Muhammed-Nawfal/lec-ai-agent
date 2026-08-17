package com.lecai.agent.task;

import java.util.ArrayList;
import java.util.List;

// merges tasks from actual source and synthetic source.
public class TaskFetcher {

    private static final String DEFAULT_OWNER = "react";
    private static final String DEFAULT_REPO = "react";
    private static final int DEFAULT_GITHUB_LIMIT = 10;

    private final GitHubIssueFetcher gitHubIssueFetcher;

    public TaskFetcher() {
        this.gitHubIssueFetcher = new GitHubIssueFetcher();
    }

    public List<Task> fetchAll() {
        List<Task> tasks = new ArrayList<>();
        tasks.addAll(gitHubIssueFetcher.fetchIssues(DEFAULT_OWNER, DEFAULT_REPO, DEFAULT_GITHUB_LIMIT));
        tasks.addAll(SyntheticTasks.all());
        return tasks;
    }
}