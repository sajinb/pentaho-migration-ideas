package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import com.pentaho.migration.step.impl.JdbcUtil;

import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Upserts each row into a database table and passes the row through unchanged.
 * Equivalent to Pentaho's InsertUpdate step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code jdbcDriver}, {@code jdbcUrl}, {@code jdbcUser}, {@code jdbcPassword}
 *   <li>{@code tableName}    — target table
 *   <li>{@code columns}      — comma-separated column names matching row field order
 *   <li>{@code keyFields}    — comma-separated column names used for the WHERE clause
 *   <li>{@code updateFields} — comma-separated column names to SET on update
 *                              (defaults to all non-key columns)
 * </ul>
 *
 * <p>All SQL exceptions are wrapped in {@link RuntimeException}.
 * The JDBC connection is opened once in {@link #configure} and reused per row.
 */
public class InsertUpdateStep extends AbstractStreamingStep {

    private Connection conn;
    private PreparedStatement selectStmt;
    private PreparedStatement updateStmt;
    private PreparedStatement insertStmt;
    private int[] keyIndices;
    private int[] updateIndices;
    private List<String> allColumns;

    @Override
    public void configure(Map<String, String> params) {
        try {
            String tableName = params.get("tableName");
            allColumns = Arrays.asList(params.get("columns").split(","));

            List<String> keyFields = Arrays.asList(params.get("keyFields").split(","));

            List<String> updateFields;
            if (params.containsKey("updateFields")) {
                updateFields = Arrays.asList(params.get("updateFields").split(","));
            } else {
                updateFields = allColumns.stream()
                        .filter(c -> !keyFields.contains(c))
                        .collect(Collectors.toList());
            }

            // Map column names to row indices
            keyIndices = keyFields.stream()
                    .mapToInt(allColumns::indexOf).toArray();
            updateIndices = updateFields.stream()
                    .mapToInt(allColumns::indexOf).toArray();

            // SELECT COUNT(*) to check existence
            String whereSql = keyFields.stream()
                    .map(k -> k + "=?")
                    .collect(Collectors.joining(" AND "));
            String updateSql = "UPDATE " + tableName + " SET "
                    + updateFields.stream().map(f -> f + "=?").collect(Collectors.joining(", "))
                    + " WHERE " + whereSql;
            String insertCols = String.join(", ", allColumns);
            String insertPlaceholders = allColumns.stream().map(c -> "?").collect(Collectors.joining(", "));
            String insertSql = "INSERT INTO " + tableName + " (" + insertCols + ") VALUES (" + insertPlaceholders + ")";

            conn = JdbcUtil.openConnection(params);
            selectStmt = conn.prepareStatement("SELECT COUNT(*) FROM " + tableName + " WHERE " + whereSql);
            updateStmt = conn.prepareStatement(updateSql);
            insertStmt = conn.prepareStatement(insertSql);
        } catch (SQLException e) {
            throw new RuntimeException("InsertUpdateStep configure failed", e);
        }
    }

    @Override
    protected Row transform(Row row) {
        try {
            // Set key params on SELECT
            for (int i = 0; i < keyIndices.length; i++) {
                selectStmt.setString(i + 1, row.getString(keyIndices[i]));
            }
            try (ResultSet rs = selectStmt.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                if (count > 0) {
                    // UPDATE: set update fields, then key fields
                    for (int i = 0; i < updateIndices.length; i++) {
                        updateStmt.setString(i + 1, row.getString(updateIndices[i]));
                    }
                    for (int i = 0; i < keyIndices.length; i++) {
                        updateStmt.setString(updateIndices.length + i + 1, row.getString(keyIndices[i]));
                    }
                    updateStmt.executeUpdate();
                } else {
                    // INSERT: all columns in order
                    for (int i = 0; i < allColumns.size(); i++) {
                        insertStmt.setString(i + 1, row.getString(i));
                    }
                    insertStmt.executeUpdate();
                }
            }
            conn.commit();
            return row;
        } catch (SQLException e) {
            throw new RuntimeException("InsertUpdateStep transform failed", e);
        }
    }
}
