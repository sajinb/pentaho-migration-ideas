package com.pentaho.migration.sort;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Demonstrates ExternalMergeSort against a real CSV file.
 *
 * Usage:
 *   java -cp target/migration-framework-1.0-SNAPSHOT.jar \
 *        com.pentaho.migration.sort.CsvSortDemo \
 *        <csvFile> <sortCol1> <sortCol2> [chunkSizeMB]
 *
 *   csvFile      — path to input CSV; first row is treated as header
 *   sortCol1     — 0-based column index for primary sort key
 *   sortCol2     — 0-based column index for secondary sort key
 *   chunkSizeMB  — optional chunk size for external sort (default 256 MB)
 *
 * Output:
 *   Sorted CSV written to <inputBaseName>_sorted.csv in the same directory.
 *   Memory and performance stats printed to stdout.
 */
public final class CsvSortDemo {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: CsvSortDemo <csvFile> <sortCol1> <sortCol2> [chunkSizeMB]");
            System.err.println("  csvFile      path to input CSV (first row = header)");
            System.err.println("  sortCol1     0-based primary sort column index");
            System.err.println("  sortCol2     0-based secondary sort column index");
            System.err.println("  chunkSizeMB  memory per sort chunk (default 256)");
            System.exit(1);
        }

        Path   inputPath   = Paths.get(args[0]);
        int    col1        = Integer.parseInt(args[1]);
        int    col2        = Integer.parseInt(args[2]);
        long   chunkMB     = args.length > 3 ? Long.parseLong(args[3]) : 256L;

        if (!Files.exists(inputPath)) {
            System.err.println("File not found: " + inputPath);
            System.exit(1);
        }

        // Derive output path: same directory, "<stem>_sorted.csv"
        String inputName   = inputPath.getFileName().toString();
        String stem        = inputName.endsWith(".csv")
                             ? inputName.substring(0, inputName.length() - 4)
                             : inputName;
        Path   outputPath  = inputPath.resolveSibling(stem + "_sorted.csv");

        // ---- Read header ---------------------------------------------------
        String[] header;
        try (BufferedReader br = Files.newBufferedReader(inputPath)) {
            String headerLine = br.readLine();
            if (headerLine == null) {
                System.err.println("Input file is empty.");
                System.exit(1);
            }
            header = parseCsvLine(headerLine);
        }

        System.out.println("=== CSV Sort Demo ===");
        System.out.println("Input  : " + inputPath + "  (" + toMB(Files.size(inputPath)) + " MB)");
        System.out.println("Output : " + outputPath);
        System.out.printf ("Columns: %d total%n", header.length);
        for (int i = 0; i < header.length; i++) {
            System.out.printf("  [%d] %s%s%n", i, header[i],
                    i == col1 ? "  <- primary key" : (i == col2 ? "  <- secondary key" : ""));
        }
        System.out.printf("Chunk  : %d MB%n%n", chunkMB);

        // ---- Build comparator: col1 ASC, then col2 ASC (nulls last) --------
        Comparator<String> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<Row> cmp = Comparator
                .comparing((Row r) -> r.getString(col1), nullsLast)
                .thenComparing((Row r) -> r.getString(col2), nullsLast);

        // ---- Configure sort ------------------------------------------------
        SortConfig config = SortConfig.builder()
                .comparator(cmp)
                .chunkSizeBytes(chunkMB * 1024L * 1024L)
                .tempDir(Paths.get(System.getProperty("java.io.tmpdir")))
                .build();

        // ---- Memory snapshot before sort -----------------------------------
        long[] before = heapSnapshot();

        // ---- Sort ----------------------------------------------------------
        long startMs = System.currentTimeMillis();

        Iterator<Row> csvRows = csvIterator(inputPath);    // streams from disk
        Iterator<Row> sorted  = new ExternalMergeSort(config).sort(csvRows);

        // ---- Write output + count rows -------------------------------------
        long rowCount = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath)) {
            // Write header
            bw.write(joinCsvLine(header));
            bw.newLine();
            // Write sorted data rows
            while (sorted.hasNext()) {
                bw.write(joinCsvLine(sorted.next().getValues()));
                bw.newLine();
                rowCount++;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        // ---- Memory snapshot after sort ------------------------------------
        long[] after = heapSnapshot();

        // ---- Print stats ---------------------------------------------------
        long outputSize = Files.size(outputPath);
        double mbPerSec = elapsedMs > 0
                ? (Files.size(inputPath) / (1024.0 * 1024.0)) / (elapsedMs / 1000.0)
                : 0;

        System.out.println("=== Sort Stats ===");
        System.out.printf("Rows sorted  : %,d%n",         rowCount);
        System.out.printf("Output size  : %s MB%n",       toMB(outputSize));
        System.out.printf("Elapsed      : %,d ms%n",      elapsedMs);
        System.out.printf("Throughput   : %.1f MB/s%n%n", mbPerSec);

        System.out.println("=== JVM Memory ===");
        System.out.printf("%-14s  %12s  %12s%n", "",        "Before sort",  "After sort");
        System.out.printf("%-14s  %12s  %12s%n", "Heap used",
                toMB(before[0]) + " MB", toMB(after[0]) + " MB");
        System.out.printf("%-14s  %12s  %12s%n", "Heap commit",
                toMB(before[1]) + " MB", toMB(after[1]) + " MB");
        System.out.printf("%-14s  %12s  %12s%n", "Heap max",
                toMB(before[2]) + " MB", toMB(after[2]) + " MB");
        System.out.println();
        System.out.println("Sorted file: " + outputPath);
    }

    // -------------------------------------------------------------------------
    // CSV streaming iterator — reads data rows lazily line by line
    // -------------------------------------------------------------------------

    /**
     * Returns a lazy iterator over data rows (header already consumed).
     * The BufferedReader is closed automatically when the iterator is exhausted.
     */
    private static Iterator<Row> csvIterator(Path path) throws IOException {
        BufferedReader br = Files.newBufferedReader(path);
        br.readLine(); // skip header

        return new Iterator<Row>() {
            private String nextLine = readNext();
            private boolean closed  = false;

            private String readNext() {
                try {
                    String line = br.readLine();
                    if (line == null) {
                        if (!closed) {
                            closed = true;
                            br.close();
                        }
                    }
                    return line;
                } catch (IOException e) {
                    throw new RuntimeException("Error reading CSV: " + e.getMessage(), e);
                }
            }

            @Override
            public boolean hasNext() {
                return nextLine != null;
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                Row row = new Row(parseCsvLine(nextLine));
                nextLine = readNext();
                return row;
            }
        };
    }

    // -------------------------------------------------------------------------
    // CSV parsing — basic RFC 4180: handles quoted fields with embedded commas
    // -------------------------------------------------------------------------

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    // peek ahead: "" inside quotes = escaped quote
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    sb.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(sb.isEmpty() ? null : sb.toString());
                    sb.setLength(0);
                } else {
                    sb.append(c);
                }
            }
        }
        fields.add(sb.isEmpty() ? null : sb.toString());
        return fields.toArray(new String[0]);
    }

    /** Joins field values back to a CSV line. Null fields become empty strings. */
    private static String joinCsvLine(String[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            String v = values[i] == null ? "" : values[i];
            // Quote if value contains comma, quote, or newline
            if (v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Memory helpers
    // -------------------------------------------------------------------------

    /**
     * Forces a GC then captures heap used / committed / max from MemoryMXBean.
     * Returns long[3]: [0]=used, [1]=committed, [2]=max — all in bytes.
     */
    private static long[] heapSnapshot() {
        System.gc();
        MemoryMXBean mx = ManagementFactory.getMemoryMXBean();
        var heap = mx.getHeapMemoryUsage();
        return new long[]{ heap.getUsed(), heap.getCommitted(), heap.getMax() };
    }

    private static String toMB(long bytes) {
        return String.format("%,d", bytes / (1024 * 1024));
    }
}
