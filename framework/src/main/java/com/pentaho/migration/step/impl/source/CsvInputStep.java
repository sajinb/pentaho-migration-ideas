package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads rows from a delimited CSV file. The first row is treated as a header
 * and not emitted as data. Equivalent to Pentaho's CsvInput step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath}  — path to the CSV file (required)
 *   <li>{@code hasHeader} — "true" (default) to skip header; "false" to include it
 * </ul>
 */
public class CsvInputStep extends AbstractSourceStep {

    private String filePath;
    private boolean hasHeader = true;

    @Override
    public void configure(Map<String, String> params) {
        filePath  = params.get("filePath");
        hasHeader = !"false".equalsIgnoreCase(params.getOrDefault("hasHeader", "true"));
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        return CsvUtil.streamRows(Paths.get(filePath), hasHeader);
    }
}
