package com.lecai.agent.agent;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.RunConfig;
import com.google.adk.runner.InMemoryRunner;
import com.google.adk.sessions.Session;
import com.google.genai.types.Content;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.Part;
import com.lecai.agent.tools.RuleCheckResult;
import com.lecai.agent.tools.RuleCheckTool;
import com.lecai.agent.tools.Verdict;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

// a class to run the agent on a single task and return the decision, falling back to RuleCheckTool if the agent fails
public final class TaskReviewer {

    private static final BaseAgent AGENT = SafetyAgent.create();

    private TaskReviewer() {
    }

    public static TaskDecision review(String taskText) {
        try {
            InMemoryRunner runner = new InMemoryRunner(AGENT);
            Session session = runner.sessionService()
                    .createSession(runner.appName(), "lec-ai-agent-" + UUID.randomUUID())
                    .blockingGet();

            Content userMsg = Content.fromParts(Part.fromText(taskText));
            RunConfig runConfig = RunConfig.builder().build();

            Optional<TaskDecision> decision = runner
                    .runAsync(session.userId(), session.id(), userMsg, runConfig)
                    .timeout(30, TimeUnit.SECONDS)
                    .toList()
                    .blockingGet()
                    .stream()
                    .flatMap(event -> event.functionCalls().stream())
                    .filter(call -> "recordOutcome".equals(call.name().orElse("")))
                    .findFirst()
                    .map(TaskReviewer::toDecision);

            return decision.orElseGet(() -> fallback(taskText, "Agent did not call recordOutcome."));
        } catch (Exception e) {
            return fallback(taskText, "LLM call failed: " + e.getMessage());
        }
    }

    private static TaskDecision toDecision(FunctionCall call) {
        Map<String, Object> args = call.args().orElse(Map.of());
        boolean flaggedUnsafe = Boolean.parseBoolean(String.valueOf(args.get("flaggedUnsafe")));
        String reason = String.valueOf(args.getOrDefault("reason", "No reason provided by agent."));
        return new TaskDecision(flaggedUnsafe, reason, false);
    }

    private static TaskDecision fallback(String taskText, String fallbackContext) {
        RuleCheckResult result = RuleCheckTool.checkRules(taskText);
        boolean flaggedUnsafe = result.verdict() != Verdict.SAFE;
        return new TaskDecision(flaggedUnsafe,
                fallbackContext + " Fell back to RuleCheckTool: " + result.reason(), true);
    }
}
