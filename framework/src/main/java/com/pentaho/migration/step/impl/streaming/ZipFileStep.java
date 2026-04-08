package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.io.*;
import java.nio.file.*;
import java.util.Map;
import java.util.zip.*;

/**
 * Zips the file named in a source column into the path named in a target column.
 * Equivalent to Pentaho's ZipFile step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code sourceColumn} — 0-based column index of file to zip
 *   <li>{@code targetColumn} — 0-based column index of destination .zip path
 * </ul>
 */
public class ZipFileStep extends AbstractStreamingStep {

    private int sourceColumn = 0;
    private int targetColumn = 1;

    @Override
    public void configure(Map<String, String> params) {
        sourceColumn = Integer.parseInt(params.getOrDefault("sourceColumn", "0"));
        targetColumn = Integer.parseInt(params.getOrDefault("targetColumn", "1"));
    }

    @Override
    protected Row transform(Row row) {
        String source = sourceColumn < row.fieldCount() ? row.getString(sourceColumn) : null;
        String target = targetColumn < row.fieldCount() ? row.getString(targetColumn) : null;
        if (source != null && target != null) {
            try {
                Path src = Paths.get(source);
                try (ZipOutputStream zos = new ZipOutputStream(
                        new BufferedOutputStream(new FileOutputStream(target)))) {
                    zos.putNextEntry(new ZipEntry(src.getFileName().toString()));
                    Files.copy(src, zos);
                    zos.closeEntry();
                }
            } catch (Exception e) {
                throw new RuntimeException("ZipFile failed: " + e.getMessage(), e);
            }
        }
        return row;
    }
}
