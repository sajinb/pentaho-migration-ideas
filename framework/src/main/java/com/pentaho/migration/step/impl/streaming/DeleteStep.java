package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Deletes a database row matching the key fields and passes the row through.
 * Equivalent to Pentaho's Delete step.
 *
 * <p><b>Phase 2 stub</b> — requires JDBC DataSource injection.
 */
public class DeleteStep extends AbstractStreamingStep {

    @Override public void configure(Map<String, String> params) {}

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "DeleteStep requires Phase 2 (JDBC DataSource) — not yet implemented.");
    }
}
