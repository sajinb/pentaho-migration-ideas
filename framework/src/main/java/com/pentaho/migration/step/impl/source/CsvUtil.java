package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Shared CSV parsing and writing utilities used by CsvInputStep, TextFileInputStep,
 * TextFileOutputStep, and CsvSortDemo.
 * RFC 4180 compliant: handles quoted fields with embedded commas and escaped quotes.
 */
public final class CsvUtil {

    private CsvUtil() {}

    /**
     * Returns a streaming iterator over data rows in a CSV file.
     * The first line is treated as a header and skipped.
     */
    public static Iterator<Row> streamRows(Path path) throws IOException {
        return streamRows(path, true);
    }

    /**
     * Returns a streaming iterator over rows in a CSV file.
     *
     * @param path       path to the CSV file
     * @param skipHeader if true, the first line is skipped
     */
    public static Iterator<Row> streamRows(Path path, boolean skipHeader) throws IOException {
        return streamRows(Files.newBufferedReader(path), skipHeader);
    }

    /**
     * Returns a streaming iterator over rows from an {@link InputStream} (e.g. SFTP).
     * The stream is closed automatically when the iterator is exhausted or if an
     * error occurs.
     *
     * @param in         input stream (UTF-8 encoded CSV)
     * @param skipHeader if true, the first line is skipped
     */
    public static Iterator<Row> streamRows(InputStream in, boolean skipHeader) throws IOException {
        return streamRows(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)),
                          skipHeader);
    }

    private static Iterator<Row> streamRows(BufferedReader br, boolean skipHeader) throws IOException {
        if (skipHeader) br.readLine();

        return new Iterator<Row>() {
            private String nextLine = readNext();
            private boolean closed  = false;

            private String readNext() {
                try {
                    String line = br.readLine();
                    if (line == null && !closed) {
                        closed = true;
                        try { br.close(); } catch (IOException ignored) {}
                    }
                    return line;
                } catch (IOException e) {
                    throw new RuntimeException("Error reading CSV stream", e);
                }
            }

            @Override public boolean hasNext() { return nextLine != null; }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                Row row  = new Row(parseLine(nextLine));
                nextLine = readNext();
                return row;
            }
        };
    }

    /** Read the header line from a CSV file and return its field names. */
    public static String[] readHeader(Path path) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(path)) {
            String line = br.readLine();
            return line != null ? parseLine(line) : new String[0];
        }
    }

    /** Parse one CSV line into an array of field values (null for empty fields). */
    public static String[] parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"'); i++;      // escaped quote
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if      (c == '"') inQuotes = true;
                else if (c == ',') { fields.add(sb.isEmpty() ? null : sb.toString()); sb.setLength(0); }
                else               sb.append(c);
            }
        }
        fields.add(sb.isEmpty() ? null : sb.toString());
        return fields.toArray(new String[0]);
    }

    /** Format a row as a CSV line (RFC 4180 quoting). */
    public static String formatLine(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : values[i];
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0)
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            else
                sb.append(v);
        }
        return sb.toString();
    }

    /**
     * Write all rows from the iterator to a CSV file.
     * @param header if non-null, written as first line
     */
    public static void writeAll(Iterator<Row> rows, String[] header, Path path) throws IOException {
        try (BufferedWriter bw = Files.newBufferedWriter(path)) {
            if (header != null) { bw.write(formatLine(header)); bw.newLine(); }
            while (rows.hasNext()) {
                bw.write(formatLine(rows.next().getValues()));
                bw.newLine();
            }
        }
    }
}
