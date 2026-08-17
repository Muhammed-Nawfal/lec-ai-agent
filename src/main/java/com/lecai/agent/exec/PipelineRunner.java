package com.lecai.agent.exec;

import com.lecai.agent.agent.TaskDecision;
import com.lecai.agent.agent.TaskReviewer;
import com.lecai.agent.db.DbConfig;
import com.lecai.agent.task.Task;
import com.lecai.agent.task.TaskFetcher;
import com.lecai.agent.tools.DatabaseTool;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs the full task pipeline (fetch -> agent review -> atomic counter
 * update -> task_log) concurrently across a thread pool, then prints a
 * correctness summary comparing the in-memory AgentStats count, the actual
 * Postgres count, and the expected count.
 *
 * Each task's line prints the live in-memory AgentStats totals at the
 * moment it finished, not just a final tally -- watching multiple different
 * thread names interleave while the count climbs by exactly one per task,
 * every time, is the actual proof of concurrency correctness this project
 * makes, deliberately without a separately-broken code path to compare
 * against.
 *
 * Explicitly calls System.exit(0) at the end: google-genai's internal HTTP
 * client (used by every TaskReviewer.review() call) leaves a connection-pool
 * thread alive for roughly a minute after the last call and isn't reachable
 * through ADK's public API to close directly -- confirmed in Phase 6. Since
 * all real work, including this summary, is done by that point, forcing exit
 * here is a deliberate choice, not a masked bug.
 */
public final class PipelineRunner {

    private static final int WORKER_COUNT = 5;
    private static final int MIN_REQUIRED_TASKS = 10;
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    /**
     * Usage: java ... PipelineRunner [taskLimit]
     * With no argument, processes every fetched task. With a number, caps
     * the run to that many tasks (useful for a shorter demo run that still
     * clears the assessment's minimum of 10 fetched/classified tasks).
     */
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== lec-ai-agent pipeline run (workers=" + WORKER_COUNT + ") ===");

        HikariDataSource dataSource = DbConfig.createDataSource();
        resetAgentStats(dataSource);

        List<Task> allTasks = new TaskFetcher().fetchAll();
        List<Task> tasks = applyLimit(allTasks, args);

        if (tasks.size() < MIN_REQUIRED_TASKS) {
            System.out.println("WARNING: only " + tasks.size() + " task(s) will be processed this run, "
                    + "below the assessment's minimum of " + MIN_REQUIRED_TASKS + ". "
                    + (allTasks.size() < MIN_REQUIRED_TASKS
                            ? "The GitHub fetch likely failed or returned too few issues -- rerun, "
                                    + "or check GitHubIssueFetcher's output above."
                            + " (Total fetched: " + allTasks.size() + ".)"
                            : "Raise the task limit argument to at least " + MIN_REQUIRED_TASKS + "."));
        }

        System.out.println("Fetched " + allTasks.size() + " tasks total, processing " + tasks.size()
                + ". Starting concurrent pipeline...\n");

        AgentStats stats = new AgentStats();
        ExecutorService pool = Executors.newFixedThreadPool(WORKER_COUNT);
        CountDownLatch latch = new CountDownLatch(tasks.size());

        for (Task task : tasks) {
            pool.submit(() -> {
                try {
                    processTask(task, dataSource, stats);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        printSummary(tasks.size(), stats, dataSource);
        System.exit(0);
    }

    private static void processTask(Task task, HikariDataSource dataSource, AgentStats stats) {
        String threadName = Thread.currentThread().getName();
        Timestamp startedAt = new Timestamp(System.currentTimeMillis());

        TaskDecision decision = TaskReviewer.review(task.fullText());

        DatabaseTool.recordOutcome(decision.flaggedUnsafe(), decision.reason());

        Timestamp finishedAt = new Timestamp(System.currentTimeMillis());
        TaskLogWriter.log(dataSource, task.id(), decision.flaggedUnsafe(), decision.reason(),
                threadName, startedAt, finishedAt);
        stats.record(decision.flaggedUnsafe());

        String verdict = decision.flaggedUnsafe() ? "UNSAFE" : "SAFE";
        String source = decision.usedFallback() ? "FALLBACK" : "AGENT";
        System.out.printf("[%s] [%s] %-24s -> %-6s [%s] | processed=%d safe=%d unsafe=%d%n",
                threadName, CLOCK.format(LocalTime.now()), task.id(), verdict, source,
                stats.processed(), stats.safe(), stats.unsafe());
        System.out.printf("[%s] [%s]   -> Reason: %s%n",
                threadName, CLOCK.format(LocalTime.now()), decision.reason());
    }

    private static List<Task> applyLimit(List<Task> tasks, String[] args) {
        if (args.length == 0) {
            return tasks;
        }
        int limit;
        try {
            limit = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.err.println("Ignoring invalid task limit argument \"" + args[0] + "\" (not a number).");
            return tasks;
        }
        return tasks.size() > limit ? tasks.subList(0, limit) : tasks;
    }

    private static void resetAgentStats(HikariDataSource dataSource) throws InterruptedException {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE agent_stats SET issues_processed = 0, issues_flagged_unsafe = 0 WHERE id = 1");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset agent_stats before run", e);
        }
    }

    private static void printSummary(int expectedProcessed, AgentStats stats, HikariDataSource dataSource) {
        int dbProcessed = -1;
        int dbFlagged = -1;
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT issues_processed, issues_flagged_unsafe FROM agent_stats WHERE id = 1")) {
            if (rs.next()) {
                dbProcessed = rs.getInt("issues_processed");
                dbFlagged = rs.getInt("issues_flagged_unsafe");
            }
        } catch (SQLException e) {
            System.err.println("Could not read final agent_stats: " + e.getMessage());
        }

        boolean correct = dbProcessed == expectedProcessed && stats.processed() == expectedProcessed;

        System.out.println();
        System.out.println("=== Summary ===");
        System.out.println("Expected processed:        " + expectedProcessed);
        System.out.println("AgentStats (in-memory):    processed=" + stats.processed()
                + " safe=" + stats.safe() + " unsafe=" + stats.unsafe());
        System.out.println("Postgres agent_stats:      processed=" + dbProcessed + " flagged_unsafe=" + dbFlagged);
        System.out.println(correct ? "RESULT: CORRECT (all counts match)" : "RESULT: MISMATCH (lost update detected)");
    }
}
