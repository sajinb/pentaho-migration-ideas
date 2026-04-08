package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Upserts each row into a database table and passes the row through.
 * Equivalent to Pentaho's InsertUpdate step.
 *
 * <p><b>Phase 2 stub</b> — requires JDBC DataSource injection.
 * Throws {@link UnsupportedOperationException} until implemented.
 */
public class InsertUpdateStep extends AbstractStreamingStep {

    @Override
    public void configure(Map<String, String> params) {}

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "InsertUpdateStep requires Phase 2 (JDBC DataSource) — not yet implemented.");
    }
}
