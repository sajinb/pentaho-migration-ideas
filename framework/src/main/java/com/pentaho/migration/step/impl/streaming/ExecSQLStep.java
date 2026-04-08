package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import com.pentaho.migration.step.impl.JdbcUtil;

import java.sql.*;
import java.util.Arrays;
import java.util.Map;

/**
 * Executes a parameterized SQL statement per row and passes the row through unchanged.
 * Equivalent to Pentaho's ExecSQL step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code jdbcDriver}, {@code jdbcUrl}, {@code jdbcUser}, {@code jdbcPassword}
 *   <li>{@code sql}         — SQL template with {@code ?} placeholders (any DML)
 *   <li>{@code paramFields} — comma-separated 0-based row field indices bound to placeholders
 *                             (may be empty/absent for parameter-less SQL)
 * </ul>
 *
 * <p>All SQL exceptions are wrapped in {@link RuntimeException}.
 */
public class ExecSQLStep extends AbstractStreamingStep {

    private Connection conn;
    private PreparedStatement ps;
    private int[] paramFieldIndices;

    @Override
    public void configure(Map<String, String> params) {
        try {
            String paramFieldsStr = params.getOrDefault("paramFields", "").trim();
            paramFieldIndices = paramFieldsStr.isEmpty()
                    ? new int[0]
                    : Arrays.stream(paramFieldsStr.split(","))
                             .mapToInt(Integer::parseInt).toArray();

            conn = JdbcUtil.openConnection(params);
            ps   = conn.prepareStatement(params.get("sql"));
        } catch (SQLException e) {
            throw new RuntimeException("ExecSQLStep configure failed", e);
        }
    }

    @Override
    protected Row transform(Row row) {
        try {
            for (int i = 0; i < paramFieldIndices.length; i++) {
                ps.setString(i + 1, row.getString(paramFieldIndices[i]));
            }
            ps.executeUpdate();
            conn.commit();
            return row; // pass-through
        } catch (SQLException e) {
            throw new RuntimeException("ExecSQLStep transform failed", e);
        }
    }
}
