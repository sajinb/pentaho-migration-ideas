package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSinkStep;
import com.pentaho.migration.step.impl.source.CsvUtil;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Writes rows to a delimited text file. Equivalent to Pentaho's TextFileOutput step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath}     — output file path (required)
 *   <li>{@code writeHeader}  — "true" (default) to write a header row; field names from "header.N"
 * </ul>
 */
public class TextFileOutputStep extends AbstractSinkStep {

    private String filePath;
    private boolean writeHeader = false;
    private BufferedWriter writer;

    @Override
    public void configure(Map<String, String> params) {
        filePath    = params.get("filePath");
        writeHeader = "true".equalsIgnoreCase(params.getOrDefault("writeHeader", "false"));
    }

    @Override
    protected void open() throws IOException {
        if (filePath == null || filePath.isBlank())
            throw new IOException(
                    "TextFileOutput: 'filePath' param is null/blank — " +
                    "check ${VAR} resolution or the KTR <file>/<name> configuration");
        writer = Files.newBufferedWriter(Paths.get(filePath));
    }

    @Override
    protected void writeRow(Row row) throws IOException {
        writer.write(CsvUtil.formatLine(row.getValues()));
        writer.newLine();
    }

    @Override
    protected void close() throws IOException {
        if (writer != null) writer.close();
    }
}
