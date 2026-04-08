package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import com.pentaho.migration.step.impl.JdbcUtil;

import java.sql.*;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Deletes a database row matching the key fields and passes the row through unchanged.
 * Equivalent to Pentaho's Delete step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code jdbcDriver}, {@code jdbcUrl}, {@code jdbcUser}, {@code jdbcPassword}
 *   <li>{@code tableName}  — target table
 *   <li>{@code columns}    — comma-separated column names matching row field order
 *   <li>{@code keyFields}  — comma-separated column names used for WHERE clause
 * </ul>
 *
 * <p>If no row matches the key, the DELETE silently affects 0 rows (Pentaho's behaviour).
 * All SQL exceptions are wrapped in {@link RuntimeException}.
 */
public class DeleteStep extends AbstractStreamingStep {

    private Connection conn;
    private PreparedStatement deleteStmt;
    private int[] keyIndices;

    @Override
    public void configure(Map<String, String> params) {
        try {
            String tableName = params.get("tableName");
            List<String> allColumns = Arrays.asList(params.get("columns").split(","));
            List<String> keyFields  = Arrays.asList(params.get("keyFields").split(","));

            keyIndices = keyFields.stream()
                    .mapToInt(allColumns::indexOf).toArray();

            String whereSql = keyFields.stream()
                    .map(k -> k + "=?")
                    .collect(Collectors.joining(" AND "));

            conn = JdbcUtil.openConnection(params);
            deleteStmt = conn.prepareStatement("DELETE FROM " + tableName + " WHERE " + whereSql);
        } catch (SQLException e) {
            throw new RuntimeException("DeleteStep configure failed", e);
        }
    }

    @Override
    protected Row transform(Row row) {
        try {
            for (int i = 0; i < keyIndices.length; i++) {
                deleteStmt.setString(i + 1, row.getString(keyIndices[i]));
            }
            deleteStmt.executeUpdate();
            conn.commit();
            return row; // pass-through
        } catch (SQLException e) {
            throw new RuntimeException("DeleteStep transform failed", e);
        }
    }
}
