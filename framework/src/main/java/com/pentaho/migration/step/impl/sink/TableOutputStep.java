package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSinkStep;
import com.pentaho.migration.step.impl.JdbcUtil;

import java.sql.*;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Writes rows to a relational table via JDBC batch INSERT.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code jdbcDriver}, {@code jdbcUrl}, {@code jdbcUser}, {@code jdbcPassword}
 *   <li>{@code tableName}    — target table
 *   <li>{@code truncateFirst} — if {@code "true"}, DELETE FROM table before inserting (default false)
 *   <li>{@code batchSize}    — rows per batch commit (default 1000)
 * </ul>
 */
public class TableOutputStep extends AbstractSinkStep {

    private Map<String, String> params;
    private Connection conn;
    private PreparedStatement ps;
    private int batchSize;
    private int batchCount;

    @Override
    public void configure(Map<String, String> p) {
        this.params = p;
    }

    @Override
    protected void open() throws Exception {
        String tableName = params.get("tableName");
        batchSize  = Integer.parseInt(params.getOrDefault("batchSize", "1000"));
        batchCount = 0;

        conn = JdbcUtil.openConnection(params); // autoCommit=false

        if ("true".equalsIgnoreCase(params.getOrDefault("truncateFirst", "false"))) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("DELETE FROM " + tableName);
            }
            conn.commit();
        }

        // Determine column count from empty result-set metadata
        int colCount;
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName + " WHERE 1=0")) {
            colCount = rs.getMetaData().getColumnCount();
        }

        String placeholders = IntStream.range(0, colCount)
                .mapToObj(i -> "?")
                .collect(Collectors.joining(", "));
        ps = conn.prepareStatement("INSERT INTO " + tableName + " VALUES (" + placeholders + ")");
    }

    @Override
    protected void writeRow(Row row) throws Exception {
        for (int i = 0; i < row.fieldCount(); i++) {
            ps.setString(i + 1, row.getString(i));
        }
        ps.addBatch();
        batchCount++;
        if (batchCount >= batchSize) {
            ps.executeBatch();
            conn.commit();
            batchCount = 0;
        }
    }

    @Override
    protected void close() throws Exception {
        try {
            if (batchCount > 0) {
                ps.executeBatch();
                conn.commit();
            }
        } finally {
            if (ps   != null) try { ps.close();   } catch (SQLException ignored) {}
            if (conn != null) try { conn.close();  } catch (SQLException ignored) {}
        }
    }
}
