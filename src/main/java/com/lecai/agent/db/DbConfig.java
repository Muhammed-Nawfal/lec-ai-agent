package com.lecai.agent.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.cdimascio.dotenv.Dotenv;

public final class DbConfig {

    private DbConfig() {
    }

    public static HikariDataSource createDataSource() {
        Dotenv dotenv = Dotenv.configure().ignoreIfMissing().load();

        String host = required(dotenv, "SUPABASE_DB_HOST");
        String port = value(dotenv, "SUPABASE_DB_PORT", "5432");
        String dbName = value(dotenv, "SUPABASE_DB_NAME", "postgres");
        String user = required(dotenv, "SUPABASE_DB_USER");
        String password = required(dotenv, "SUPABASE_DB_PASSWORD");

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://" + host + ":" + port + "/" + dbName);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("lec-ai-agent-pool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(3);
        return new HikariDataSource(config);
    }

    private static String required(Dotenv dotenv, String key) {
        String val = value(dotenv, key, null);
        if (val == null || val.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key);
        }
        return val;
    }

    private static String value(Dotenv dotenv, String key, String fallback) {
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        String fromDotenv = dotenv.get(key);
        if (fromDotenv != null && !fromDotenv.isBlank()) {
            return fromDotenv;
        }
        return fallback;
    }
}
