package com.pentaho.migration.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.logging.Logger;

/**
 * Bootstraps the JDBC data source using plain {@link DriverManager}.
 * No Spring Data JPA or connection pool required — H2 embedded is used for dev/test,
 * and any JDBC-compatible database works in production via environment variables.
 */
@Component
public class DatabaseConfig {

    private static final Logger LOG = Logger.getLogger(DatabaseConfig.class.getName());

    private final String url;
    private final String username;
    private final String password;

    public DatabaseConfig(
            @Value("${db.url}") String url,
            @Value("${db.username:}") String username,
            @Value("${db.password:}") String password) throws Exception {
        this.url      = url;
        this.username = username == null ? "" : username;
        this.password = password == null ? "" : password;
        loadDriver();
        initSchema();
        LOG.info("Database initialised: " + url);
    }

    /** Opens a new JDBC connection. Caller is responsible for closing it. */
    public Connection getConnection() throws SQLException {
        if (username.isBlank()) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    // -------------------------------------------------------------------------

    private void loadDriver() throws ClassNotFoundException {
        if (url.contains(":h2:")) {
            Class.forName("org.h2.Driver");
        } else if (url.contains(":postgresql:")) {
            Class.forName("org.postgresql.Driver");
        } else if (url.contains(":oracle:")) {
            Class.forName("oracle.jdbc.OracleDriver");
        } else if (url.contains(":mysql:")) {
            Class.forName("com.mysql.cj.jdbc.Driver");
        }
        // Other drivers self-register via ServiceLoader
    }

    private void initSchema() throws SQLException {
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS projects (
                    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
                    name        VARCHAR(255) NOT NULL,
                    status      VARCHAR(30)  NOT NULL,
                    error_message TEXT,
                    created_at  TIMESTAMP    NOT NULL,
                    updated_at  TIMESTAMP    NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS project_files (
                    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
                    project_id  VARCHAR(36)  NOT NULL,
                    filename    VARCHAR(500) NOT NULL,
                    file_type   VARCHAR(10)  NOT NULL,
                    content     BLOB         NOT NULL,
                    size_bytes  BIGINT       NOT NULL,
                    created_at  TIMESTAMP    NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS yaml_definitions (
                    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
                    project_id       VARCHAR(36)  NOT NULL,
                    filename         VARCHAR(500) NOT NULL,
                    definition_type  VARCHAR(20)  NOT NULL,
                    content          TEXT         NOT NULL,
                    created_at       TIMESTAMP    NOT NULL
                )""");

            stmt.execute("""
                CREATE TABLE IF NOT EXISTS job_executions (
                    id            VARCHAR(36) NOT NULL PRIMARY KEY,
                    project_id    VARCHAR(36) NOT NULL,
                    status        VARCHAR(20) NOT NULL,
                    started_at    TIMESTAMP,
                    completed_at  TIMESTAMP,
                    duration_ms   BIGINT,
                    error_message TEXT,
                    created_at    TIMESTAMP   NOT NULL
                )""");
        }
    }
}
