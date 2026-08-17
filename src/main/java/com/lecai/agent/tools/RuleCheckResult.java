package com.lecai.agent.tools;

public record RuleCheckResult(
        Verdict verdict,
        String matchedRule,
        String reason
) {
}