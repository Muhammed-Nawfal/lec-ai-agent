package com.lecai.agent.tools;

import com.google.adk.tools.Annotations.Schema;

import java.util.Map;

/**
 * Captures the agent's final decision as structured tool-call arguments,
 * with zero side effects. TaskReviewer reads the decision back off this
 * call's arguments in the event stream.
 *
 * This is a separate tool from DatabaseTool specifically because of a bug
 * found in testing: an LLM can call a tool more times than instructed. When
 * the agent's decision-tool also performed the real database write, the
 * agent occasionally called it twice in a single turn, silently
 * double-incrementing the shared counter -- each individual call was
 * atomically correct, but nothing enforced "exactly once." Splitting
 * "the agent states its decision" (this tool, side-effect-free, harmless to
 * call any number of times) from "the database gets updated" (DatabaseTool,
 * called exactly once by the deterministic orchestrator, never directly by
 * the LLM) removes the possibility of that bug entirely, rather than just
 * making it less likely.
 */
public class DecisionTool {

    @Schema(description = "States your final safe/unsafe decision for this task, with a short reason. "
            + "Call this exactly once, as the last thing you do. This tool has no side effects itself -- "
            + "the actual outcome is recorded separately by the system after you call it.")
    public static Map<String, Object> recordDecision(
            @Schema(name = "flaggedUnsafe",
                    description = "true if the task's final decision was unsafe/rejected, false if approved as safe.",
                    optional = false)
            boolean flaggedUnsafe,
            @Schema(name = "reason",
                    description = "A short, one-sentence explanation of why this decision was reached.",
                    optional = false)
            String reason) {
        return Map.of("status", "recorded", "flaggedUnsafe", flaggedUnsafe, "reason", reason);
    }
}
