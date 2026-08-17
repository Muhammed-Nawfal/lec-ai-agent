package com.lecai.agent.agent;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Paces how often worker threads are allowed to start a new agent
 * conversation with Gemini, shared across every TaskReviewer.review() call.
 *
 * The free-tier quota on gemini-flash-lite-latest is 15 requests/minute, but
 * a single task's conversation can cost 2-4 real API calls (one per
 * tool-calling turn: decide -> call checkRules -> decide -> maybe
 * checkUrlReputation -> decide -> call recordDecision). Without pacing,
 * 5 concurrent workers each starting a task at once burst far past 15/min
 * in the first few seconds, and most calls fall back to RuleCheckTool --
 * which is handled correctly, but defeats the point of showing live
 * reasoning. This throttles at 5 task-starts/minute (1 token every 12s),
 * budgeting for up to 3 calls/task to stay under the real 15/min ceiling
 * with some margin. It cannot pace the calls *within* one task's multi-turn
 * conversation -- ADK doesn't expose a hook for that -- only how many tasks
 * begin per minute.
 *
 * Starts with only 1 permit available, not MAX_PERMITS -- a Semaphore
 * constructed with N permits makes all N available immediately, which would
 * let 5 tasks begin simultaneously at the very start of a run before any
 * pacing has happened at all (confirmed causing 429s in testing even on a
 * fresh API key/quota). Starting at 1 and building up via the same 12s
 * refill schedule spreads the very first tasks out too, not just later ones.
 */
public final class GeminiRateLimiter {

    private static final int MAX_PERMITS = 5;
    private static final int INITIAL_PERMITS = 1;
    private static final int REFILL_INTERVAL_SECONDS = 12;

    private static final Semaphore PERMITS = new Semaphore(INITIAL_PERMITS);
    private static final ScheduledExecutorService REFILL_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "gemini-rate-limiter-refill");
                thread.setDaemon(true);
                return thread;
            });

    static {
        REFILL_SCHEDULER.scheduleAtFixedRate(GeminiRateLimiter::refillOnePermit,
                REFILL_INTERVAL_SECONDS, REFILL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private GeminiRateLimiter() {
    }

    /** Blocks the calling thread until a permit is available. */
    public static void acquire() throws InterruptedException {
        PERMITS.acquire();
    }

    private static void refillOnePermit() {
        if (PERMITS.availablePermits() < MAX_PERMITS) {
            PERMITS.release();
        }
    }
}
