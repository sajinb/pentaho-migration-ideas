package com.pentaho.migration.step.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/**
 * Shared JDBC helper: opens a connection from a params map.
 * <p>
 * Expected param keys: {@code jdbcDriver}, {@code jdbcUrl}, {@code jdbcUser}, {@code jdbcPassword}.
 * The returned connection has {@code autoCommit=false}; callers must commit explicitly.
 */
public final class JdbcUtil {

    private JdbcUtil() {}

    public static Connection openConnection(Map<String, String> params) throws SQLException {
        String driver   = params.get("jdbcDriver");
        String url      = params.get("jdbcUrl");
        String user     = params.getOrDefault("jdbcUser", "");
        String password = params.getOrDefault("jdbcPassword", "");

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found on classpath: " + driver, e);
        }

        Connection conn = DriverManager.getConnection(url, user, password);
        conn.setAutoCommit(false);
        return conn;
    }
}
