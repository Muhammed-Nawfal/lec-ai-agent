package com.lecai.agent.task;

// The common shape both sources of tasks (GitHub and synthetic) share, so they can be processed in a uniform way.
public record Task(
        String id,
        TaskSource source,
        String title,
        String body,
        String url
) {
    public String fullText() {
        return title + "\n" + body;
    }
}