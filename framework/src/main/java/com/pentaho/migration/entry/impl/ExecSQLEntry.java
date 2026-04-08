package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Phase 2 stub: executes a SQL statement. Requires JDBC DataSource. */
public class ExecSQLEntry implements JobEntry {
    @Override public boolean execute(Map<String, String> context) {
        throw new UnsupportedOperationException("ExecSQLEntry requires Phase 2 JDBC DataSource.");
    }
}
