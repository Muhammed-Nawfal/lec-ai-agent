package com.lecai.agent.agent;

public record TaskDecision(boolean flaggedUnsafe, String reason, boolean usedFallback) {
}
