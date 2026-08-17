package com.lecai.agent.tools;

import com.google.adk.tools.Annotations.Schema;
import com.lecai.agent.db.DbConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

public class DatabaseTool {

    private static final HikariDataSource DATA_SOURCE = DbConfig.createDataSource();

    private static final String UPDATE_SQL =
            "UPDATE agent_stats "
                    + "SET issues_processed = issues_processed + 1, "
                    + "    issues_flagged_unsafe = issues_flagged_unsafe + CASE WHEN ? THEN 1 ELSE 0 END "
                    + "WHERE id = 1 "
                    + "RETURNING issues_processed, issues_flagged_unsafe";

    @Schema(description = "Records a task's final outcome by atomically incrementing the shared "
            + "agent_stats counters in Postgres: issues_processed always increases by 1, and "
            + "issues_flagged_unsafe increases by 1 only if flaggedUnsafe is true. Call this exactly "
            + "once, after you have reached your final decision for the task -- not before, and not "
            + "as a signal to reason about. Pass a short, one-sentence reason explaining the decision; "
            + "it is not stored in agent_stats, but the orchestrator reads it back from this call to "
            + "log why you decided what you did.")
    public static Map<String, Object> recordOutcome(
            @Schema(name = "flaggedUnsafe",
                    description = "true if the task's final decision was unsafe/rejected, false if approved as safe.",
                    optional = false)
            boolean flaggedUnsafe,
            @Schema(name = "reason",
                    description = "A short, one-sentence explanation of why this decision was reached.",
                    optional = false)
            String reason) {
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
