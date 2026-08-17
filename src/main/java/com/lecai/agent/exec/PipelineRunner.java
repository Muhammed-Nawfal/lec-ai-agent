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
 * Every database write is logged with a millisecond-precision start/end
 * timestamp specifically so overlap between threads is provable by reading
 * the log, not just asserted: if one task's WRITE START timestamp falls
 * before another task's WRITE END timestamp, those two writes were
 * genuinely in flight against the same row at the same time -- and the
 * final count is still exactly correct. That is the actual proof of
 * concurrency correctness this project makes, deliberately without a
 * separately-broken code path to compare against.
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

    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== lec-ai-agent pipeline run (workers=" + WORKER_COUNT + ") ===");

        HikariDataSource dataSource = DbConfig.createDataSource();
        resetAgentStats(dataSource);

        List<Task> tasks = new TaskFetcher().fetchAll();
        System.out.println("Fetched " + tasks.size() + " tasks. Starting concurrent pipeline...\n");

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

        long writeStart = System.currentTimeMillis();
        System.out.printf("[%s] [%d] DB WRITE START task=%s%n", threadName, writeStart, task.id());
        DatabaseTool.recordOutcome(decision.flaggedUnsafe(), decision.reason());
        long writeEnd = System.currentTimeMillis();
        System.out.printf("[%s] [%d] DB WRITE END   task=%s (%dms)%n",
                threadName, writeEnd, task.id(), writeEnd - writeStart);

        Timestamp finishedAt = new Timestamp(System.currentTimeMillis());
        TaskLogWriter.log(dataSource, task.id(), decision.flaggedUnsafe(), decision.reason(),
                threadName, startedAt, finishedAt);
        stats.record(decision.flaggedUnsafe());

        System.out.printf("[%s] done: %-24s flaggedUnsafe=%-5b fallback=%-5b processedSoFar=%d%n",
                threadName, task.id(), decision.flaggedUnsafe(), decision.usedFallback(), stats.processed());
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
