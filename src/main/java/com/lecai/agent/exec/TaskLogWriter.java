package com.lecai.agent.exec;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;

public final class TaskLogWriter {

    private static final String INSERT_SQL =
            "INSERT INTO task_log (task_id, classification, reason, thread_name, started_at, finished_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    private TaskLogWriter() {
    }

    public static void log(HikariDataSource dataSource, String taskId, boolean flaggedUnsafe, String reason,
                            String threadName, Timestamp startedAt, Timestamp finishedAt) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_SQL)) {
            stmt.setString(1, taskId);
            stmt.setString(2, flaggedUnsafe ? "UNSAFE" : "SAFE");
            stmt.setString(3, reason);
            stmt.setString(4, threadName);
            stmt.setTimestamp(5, startedAt);
            stmt.setTimestamp(6, finishedAt);
            stmt.executeUpdate();
        } catch (SQLException e) {
            System.err.println("[" + threadName + "] task_log insert failed for " + taskId + ": " + e.getMessage());
        }
    }
}
