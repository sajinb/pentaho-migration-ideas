package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Counts lines in the file named in a row field and appends the count.
 * Equivalent to Pentaho's GetFilesRowsCount step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}     — 0-based column index containing the file path
 *   <li>{@code hasHeader}  — "true" (default) to subtract 1 for header
 * </ul>
 */
public class GetFilesRowsCountStep extends AbstractStreamingStep {

    private int     column    = 0;
    private boolean hasHeader = true;

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.getOrDefault("column", "0"));
        hasHeader = !"false".equalsIgnoreCase(params.getOrDefault("hasHeader", "true"));
    }

    @Override
    protected Row transform(Row row) {
        String filePath = column < row.fieldCount() ? row.getString(column) : null;
        String count    = null;
        if (filePath != null) {
            try (BufferedReader br = Files.newBufferedReader(Paths.get(filePath))) {
                long lines = br.lines().count();
                count = String.valueOf(hasHeader ? Math.max(0, lines - 1) : lines);
            } catch (Exception ignored) {}
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = count;
        return new Row(nw);
    }
}
