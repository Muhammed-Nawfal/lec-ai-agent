package com.lecai.agent.exec;

import com.lecai.agent.db.DbConfig;
import com.lecai.agent.task.Task;
import com.lecai.agent.task.TaskFetcher;
import com.lecai.agent.tools.DatabaseTool;
import com.lecai.agent.tools.RuleCheckResult;
import com.lecai.agent.tools.RuleCheckTool;
import com.lecai.agent.tools.Verdict;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chaos test harness: proves the atomic counter path stays correct under
 * adversarial concurrent timing by running the same many-tasks-at-once
 * scenario repeatedly (default 50x) and checking the final count matches
 * exactly, every single time.
 *
 * Deliberately bypasses TaskReviewer (the live Gemini pipeline) in favor of
 * calling RuleCheckTool directly. Phase 6 already proved the LLM reasons
 * correctly with hard evidence; re-running it here would only add rate-limit
 * latency and desynchronize worker arrival at the database, working against
 * the point of a concurrency stress test. This harness isolates what it's
 * actually testing: does the counter update survive many threads hitting it
 * at once, not whether the LLM classifies correctly.
 *
 * Usage: java ... StressTestRunner [runCount]   (default: 50)
 */
public final class StressTestRunner {

    private static final int DEFAULT_RUN_COUNT = 50;
    private static final int WORKER_COUNT = 8;

    public static void main(String[] args) throws InterruptedException {
        int runCount = (args.length > 0) ? Integer.parseInt(args[0]) : DEFAULT_RUN_COUNT;

        HikariDataSource dataSource = DbConfig.createDataSource();
        List<Task> tasks = new TaskFetcher().fetchAll();
        System.out.println("Loaded " + tasks.size() + " tasks once; reusing across all "
                + runCount + " runs, " + WORKER_COUNT + " workers per run.\n");

        int failures = 0;
        for (int run = 1; run <= runCount; run++) {
            resetAgentStats(dataSource);
            AgentStats stats = new AgentStats();
            ExecutorService pool = Executors.newFixedThreadPool(WORKER_COUNT);
            CountDownLatch latch = new CountDownLatch(tasks.size());

            for (Task task : tasks) {
                pool.submit(() -> {
                    try {
                        RuleCheckResult result = RuleCheckTool.checkRules(task.fullText());
                        boolean flaggedUnsafe = result.verdict() != Verdict.SAFE;
                        DatabaseTool.recordOutcome(flaggedUnsafe, result.reason());
                        stats.record(flaggedUnsafe);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            latch.await();
            pool.shutdown();

            int dbProcessed = readDbProcessed(dataSource);
            boolean correct = dbProcessed == tasks.size() && stats.processed() == tasks.size();
            if (correct) {
                System.out.printf("[run %2d/%d] OK (processed=%d)%n", run, runCount, dbProcessed);
            } else {
                failures++;
                System.out.printf("[run %2d/%d] MISMATCH expected=%d db=%d inMemory=%d%n",
                        run, runCount, tasks.size(), dbProcessed, stats.processed());
            }
        }

        System.out.println();
        System.out.println("=== Chaos test results (" + runCount + " runs, "
                + tasks.size() + " tasks/run, " + WORKER_COUNT + " workers/run) ===");
        System.out.println("ATOMIC: " + failures + " / " + runCount + " runs had a lost update");
        System.exit(0);
    }

    private static void resetAgentStats(HikariDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("UPDATE agent_stats SET issues_processed = 0, issues_flagged_unsafe = 0 WHERE id = 1");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset agent_stats before run", e);
        }
    }

    private static int readDbProcessed(HikariDataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT issues_processed FROM agent_stats WHERE id = 1")) {
            return rs.next() ? rs.getInt("issues_processed") : -1;
        } catch (SQLException e) {
            System.err.println("Could not read agent_stats: " + e.getMessage());
            return -1;
        }
    }
}
