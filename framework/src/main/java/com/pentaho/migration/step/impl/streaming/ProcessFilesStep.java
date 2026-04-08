package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.nio.file.*;
import java.util.Map;

/**
 * Performs a file operation (copy, move, delete) on a file named in a row field.
 * Equivalent to Pentaho's ProcessFiles step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code sourceColumn}  — 0-based column index of source file path
 *   <li>{@code targetColumn}  — 0-based column index of target file path (for copy/move)
 *   <li>{@code operation}     — "COPY", "MOVE", "DELETE"
 * </ul>
 */
public class ProcessFilesStep extends AbstractStreamingStep {

    private int    sourceColumn = 0;
    private int    targetColumn = 1;
    private String operation    = "COPY";

    @Override
    public void configure(Map<String, String> params) {
        sourceColumn = Integer.parseInt(params.getOrDefault("sourceColumn", "0"));
        targetColumn = Integer.parseInt(params.getOrDefault("targetColumn", "1"));
        operation    = params.getOrDefault("operation", "COPY").toUpperCase();
    }

    @Override
    protected Row transform(Row row) {
        String source = sourceColumn < row.fieldCount() ? row.getString(sourceColumn) : null;
        String target = targetColumn < row.fieldCount() ? row.getString(targetColumn) : null;
        if (source != null) {
            try {
                Path src = Paths.get(source);
                switch (operation) {
                    case "COPY"   -> Files.copy(src, Paths.get(target), StandardCopyOption.REPLACE_EXISTING);
                    case "MOVE"   -> Files.move(src, Paths.get(target), StandardCopyOption.REPLACE_EXISTING);
                    case "DELETE" -> Files.deleteIfExists(src);
                }
            } catch (Exception e) {
                throw new RuntimeException("ProcessFiles failed: " + e.getMessage(), e);
            }
        }
        return row;
    }
}
