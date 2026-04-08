package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;
import com.pentaho.migration.step.impl.JdbcUtil;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads rows from a relational table (or arbitrary SELECT query) via JDBC.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code jdbcDriver}   — fully-qualified JDBC driver class name
 *   <li>{@code jdbcUrl}      — JDBC connection URL
 *   <li>{@code jdbcUser}     — database username (optional)
 *   <li>{@code jdbcPassword} — database password (optional)
 *   <li>{@code query}        — raw SQL SELECT (takes precedence over tableName)
 *   <li>{@code tableName}    — table to SELECT * FROM (used when query is absent)
 *   <li>{@code whereClause}  — WHERE clause appended when using tableName (optional)
 * </ul>
 *
 * <p>All rows are eagerly materialized so the JDBC connection is closed before returning.
 */
public class TableInputStep extends AbstractSourceStep {

    private Map<String, String> params;

    @Override
    public void configure(Map<String, String> p) {
        this.params = p;
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        String sql;
        if (params.containsKey("query")) {
            sql = params.get("query");
        } else {
            String table = params.get("tableName");
            String where = params.get("whereClause");
            sql = "SELECT * FROM " + table + (where != null ? " WHERE " + where : "");
        }

        Connection conn = JdbcUtil.openConnection(params);
        conn.setAutoCommit(true); // read-only; no transaction needed

        List<Row> rows = new ArrayList<>();
        try (Statement stmt = conn.createStatement(
                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            stmt.setFetchSize(1000);
            try (ResultSet rs = stmt.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int colCount = meta.getColumnCount();
                while (rs.next()) {
                    String[] values = new String[colCount];
                    for (int i = 0; i < colCount; i++) {
                        values[i] = rs.getString(i + 1); // JDBC is 1-indexed; null is preserved
                    }
                    rows.add(new Row(values));
                }
            }
        } finally {
            conn.close();
        }
        return rows.isEmpty() ? Collections.emptyIterator() : rows.iterator();
    }
}
