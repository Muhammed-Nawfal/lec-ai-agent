package com.lecai.agent.tools;

import com.google.adk.tools.Annotations.Schema;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// a tool used by the agent to classify tasks as safe or unsafe based on known patterns
public class RuleCheckTool {

    private record InjectionPattern(Pattern pattern, String label) {
    }

    private static final List<InjectionPattern> INJECTION_PATTERNS = List.of(
            new InjectionPattern(
                    Pattern.compile("ignore\\s+(all\\s+)?(the\\s+)?previous\\s+instructions?", Pattern.CASE_INSENSITIVE),
                    "ignore previous instructions"),
            new InjectionPattern(
                    Pattern.compile("you'?re\\s+now|you\\s+are\\s+now", Pattern.CASE_INSENSITIVE),
                    "you are now"),
            new InjectionPattern(
                    Pattern.compile("system\\s+prompt", Pattern.CASE_INSENSITIVE),
                    "system prompt"),
            new InjectionPattern(
                    Pattern.compile("disregard\\s+(the\\s+|everything\\s+)?above", Pattern.CASE_INSENSITIVE),
                    "disregard the above")
    );

    private static final Pattern BACKTICK_SHELL_CMD = Pattern.compile(
            "`[^`]*\\b(rm|curl|wget|sudo|chmod|bash|sh|eval)\\b[^`]*`", Pattern.CASE_INSENSITIVE);

    private static final Pattern PIPE_TO_SHELL = Pattern.compile(
            "\\|\\s*(bash|sh|zsh)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s)\"'>]+");

    @Schema(description = "Runs fast local pattern checks on a task's text for known prompt-injection "
            + "phrasing, dangerous shell syntax, and URLs. Returns one of three verdicts: SAFE (no "
            + "pattern matched), NEEDS_VERIFICATION (the text references a URL that must be checked "
            + "with UrlhausCheckTool before you can decide), or UNSAFE (injection phrasing or a "
            + "dangerous shell pattern matched). This only catches known, fixed patterns -- it cannot "
            + "recognize novel attack phrasing it has no rule for. None of its verdicts are final: weigh "
            + "them alongside your own reading of the task text and, for NEEDS_VERIFICATION, the "
            + "UrlhausCheckTool result, rather than treating any of them as an automatic decision.")
    public static RuleCheckResult checkRules(
            @Schema(name = "taskText", description = "The raw title and body text of the task to inspect.", optional = false)
            String taskText) {
        String text = taskText == null ? "" : taskText;

        for (InjectionPattern injection : INJECTION_PATTERNS) {
            Matcher matcher = injection.pattern().matcher(text);
            if (matcher.find()) {
                return new RuleCheckResult(Verdict.UNSAFE, "injection-phrasing",
                        "Injection phrasing detected (matched \"" + injection.label() + "\"): \""
                                + matcher.group().trim() + "\"");
            }
        }

        Matcher backtickMatcher = BACKTICK_SHELL_CMD.matcher(text);
        if (backtickMatcher.find()) {
            return new RuleCheckResult(Verdict.UNSAFE, "shell-metacharacter",
                    "Shell command inside backticks detected: \"" + backtickMatcher.group() + "\"");
        }
        if (text.contains("$(")) {
            return new RuleCheckResult(Verdict.UNSAFE, "shell-metacharacter",
                    "Shell command substitution detected: \"$(\"");
        }
        if (text.toLowerCase().contains("rm -rf")) {
            return new RuleCheckResult(Verdict.UNSAFE, "shell-metacharacter",
                    "Destructive shell command detected: \"rm -rf\"");
        }
        Matcher pipeMatcher = PIPE_TO_SHELL.matcher(text);
        if (pipeMatcher.find()) {
            return new RuleCheckResult(Verdict.UNSAFE, "shell-metacharacter",
                    "Pipe-to-shell pattern detected: \"" + pipeMatcher.group() + "\"");
        }

        Matcher urlMatcher = URL_PATTERN.matcher(text);
        if (urlMatcher.find()) {
            return new RuleCheckResult(Verdict.NEEDS_VERIFICATION, "unverified-url",
                    "Task references a URL: \"" + urlMatcher.group()
                            + "\". Not yet checked against an external reputation source.");
        }

        return new RuleCheckResult(Verdict.SAFE, "none", "No rule violations found");
    }
}