package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Appends a boolean field indicating whether the file named in a row field exists.
 * Equivalent to Pentaho's FileExists step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column} — 0-based column index containing the file path to check
 * </ul>
 */
public class FileExistsStep extends AbstractStreamingStep {

    private int column;

    @Override
    public void configure(Map<String, String> params) {
        column = Integer.parseInt(params.getOrDefault("column", "0"));
    }

    @Override
    protected Row transform(Row row) {
        String filePath = column < row.fieldCount() ? row.getString(column) : null;
        boolean exists  = filePath != null && Files.exists(Paths.get(filePath));
        String[] old    = row.getValues();
        String[] nw     = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length]  = String.valueOf(exists);
        return new Row(nw);
    }
}
