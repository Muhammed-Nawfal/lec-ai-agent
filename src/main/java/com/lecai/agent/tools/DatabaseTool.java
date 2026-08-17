package com.lecai.agent.tools;

import com.lecai.agent.db.DbConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * Performs the single atomic UPDATE ... RETURNING statement that increments
 * the shared agent_stats row. Not an ADK tool and never called directly by
 * the LLM -- only the deterministic orchestrator (PipelineRunner,
 * StressTestRunner) calls this, exactly once per task, after reading the
 * agent's decision back from DecisionTool. That split exists because
 * testing showed the agent could call a write-capable tool more than once
 * per task; DecisionTool is side-effect-free so that no longer matters, and
 * this class is now the only thing that ever touches agent_stats.
 */
public class DatabaseTool {

    private static final HikariDataSource DATA_SOURCE = DbConfig.createDataSource();

    private static final String UPDATE_SQL =
            "UPDATE agent_stats "
                    + "SET issues_processed = issues_processed + 1, "
                    + "    issues_flagged_unsafe = issues_flagged_unsafe + CASE WHEN ? THEN 1 ELSE 0 END "
                    + "WHERE id = 1 "
                    + "RETURNING issues_processed, issues_flagged_unsafe";

    public static Map<String, Object> recordOutcome(boolean flaggedUnsafe, String reason) {
        try (Connection conn = DATA_SOURCE.getConnection();
             PreparedStatement stmt = conn.prepareStatement(UPDATE_SQL)) {
            stmt.setBoolean(1, flaggedUnsafe);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Map.of(
                            "status", "success",
                            "issuesProcessed", rs.getInt("issues_processed"),
                            "issuesFlaggedUnsafe", rs.getInt("issues_flagged_unsafe"),
                            "reason", reason);
                }
                return Map.of("status", "error", "message", "agent_stats row with id=1 not found");
            }
        } catch (SQLException e) {
            return Map.of("status", "error", "message", "Database update failed: " + e.getMessage());
        }
    }
}
