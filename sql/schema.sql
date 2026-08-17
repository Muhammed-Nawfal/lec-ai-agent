-- lec-ai-agent schema
-- Run this once against a fresh Supabase (or any) Postgres database, in the
-- SQL editor. Safe to re-run: uses IF NOT EXISTS / ON CONFLICT so it won't
-- error or duplicate the seed row if it's already been applied.
--
-- Verified live against the project's actual Supabase database -- this is
-- exactly what's running, not just a reference copy. Also included inline
-- in README.md.

-- Shared counter row that concurrent task executions race to update.
-- The id=1 CHECK constraint enforces "exactly one row" at the schema level,
-- since the concurrency proof only means anything if there's a single row
-- every worker thread is contending over.
CREATE TABLE IF NOT EXISTS agent_stats (
    id INTEGER PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    issues_processed INTEGER NOT NULL DEFAULT 0,
    issues_flagged_unsafe INTEGER NOT NULL DEFAULT 0
);

INSERT INTO agent_stats (id, issues_processed, issues_flagged_unsafe)
VALUES (1, 0, 0)
ON CONFLICT (id) DO NOTHING;

-- Append-only audit log: one row per task execution, written once the
-- task has finished (started_at/finished_at both known at insert time),
-- so no UPDATE path is needed on this table at all.
-- REJECTED is defined for schema completeness but isn't currently written --
-- the shipped design collapses safety decisions to SAFE/UNSAFE at the point
-- of logging.
CREATE TABLE IF NOT EXISTS task_log (
    id SERIAL PRIMARY KEY,
    task_id TEXT NOT NULL,
    classification TEXT NOT NULL CHECK (classification IN ('SAFE', 'UNSAFE', 'REJECTED')),
    reason TEXT,
    thread_name TEXT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ NOT NULL
);
