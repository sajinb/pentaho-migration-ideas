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
 *   <li>{@code filePath}    — output file path (required)
 *   <li>{@code writeHeader} — "true" to write a CSV header row before data rows
 *   <li>{@code fieldNames}  — comma-separated column names used as header values
 *   <li>{@code outputCols}  — comma-separated 0-based column indices to write, in order.
 *                             When absent all columns are written.
 * </ul>
 */
public class TextFileOutputStep extends AbstractSinkStep {

    private String   filePath;
    private boolean  writeHeader = false;
    private String[] fieldNames;  // header column names (from KTR <fields>/<field>/<name>)
    private int[]    outputCols;  // null = write all columns
    private BufferedWriter writer;

    @Override
    public void configure(Map<String, String> params) {
        filePath    = params.get("filePath");
        writeHeader = "true".equalsIgnoreCase(params.getOrDefault("writeHeader", "false"));

        String fnames = params.get("fieldNames");
        if (fnames != null && !fnames.isBlank()) {
            fieldNames = fnames.split(",");
        }

        String cols = params.get("outputCols");
        if (cols != null && !cols.isBlank()) {
            String[] tokens = cols.split(",");
            outputCols = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                try { outputCols[i] = Integer.parseInt(tokens[i].trim()); }
                catch (NumberFormatException e) { outputCols[i] = -1; } // unresolved name → skip
            }
        }
    }

    @Override
    protected void open() throws IOException {
        if (filePath == null || filePath.isBlank())
            throw new IOException(
                    "TextFileOutput: 'filePath' param is null/blank — " +
                    "check ${VAR} resolution or the KTR <file>/<name> configuration");
        writer = Files.newBufferedWriter(Paths.get(filePath));
        if (writeHeader && fieldNames != null && fieldNames.length > 0) {
            writer.write(CsvUtil.formatLine(fieldNames));
            writer.newLine();
        }
    }

    @Override
    protected void writeRow(Row row) throws IOException {
        String[] values = selectColumns(row);
        writer.write(CsvUtil.formatLine(values));
        writer.newLine();
    }

    private String[] selectColumns(Row row) {
        if (outputCols == null) return row.getValues();
        String[] out = new String[outputCols.length];
        for (int i = 0; i < outputCols.length; i++) {
            int col = outputCols[i];
            out[i] = (col >= 0 && col < row.fieldCount()) ? row.getString(col) : null;
        }
        return out;
    }

    @Override
    protected void close() throws IOException {
        if (writer != null) writer.close();
    }
}
