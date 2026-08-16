package com.lecai.agent;

import com.lecai.agent.db.DbConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Phase 2 smoke test: confirms the HikariCP pool can actually reach
 * Supabase and read the seeded agent_stats row. Replaced by real
 * fetch/classify/execute wiring in later phases.
 */
public class Main {
    public static void main(String[] args) throws Exception {
        try (HikariDataSource ds = DbConfig.createDataSource()) {
            try (Connection conn = ds.getConnection();
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT issues_processed, issues_flagged_unsafe FROM agent_stats WHERE id = 1")) {
                if (rs.next()) {
                    System.out.println("Connected via HikariCP pool. agent_stats row: "
                            + "issues_processed=" + rs.getInt("issues_processed")
                            + ", issues_flagged_unsafe=" + rs.getInt("issues_flagged_unsafe"));
                } else {
                    System.out.println("Connected, but agent_stats has no row with id=1.");
                }
            }
        }
    }
}
