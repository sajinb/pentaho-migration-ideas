package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Map;

/**
 * Reads rows from a delimited text file. Equivalent to Pentaho's TextFileInput step.
 * For Phase 1, delegates to CsvUtil (comma-separated, with optional header).
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath}   — path to the file (required)
 *   <li>{@code hasHeader}  — "true" (default) to skip first row
 *   <li>{@code separator}  — field separator, default "," (comma)
 * </ul>
 */
public class TextFileInputStep extends AbstractSourceStep {

    private String  filePath;
    private boolean hasHeader  = true;

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
