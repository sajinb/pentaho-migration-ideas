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
 * Demonstrates ExternalMergeSort against a real CSV file, with output formatted
 * to match the com.google.code.externalsorting benchmark for direct comparison.
 *
 * Usage:
 *   java -Xmx4g -cp target/migration-framework-1.0-SNAPSHOT.jar \
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
 *   Performance and heap stats printed to stdout in comparison format:
 *
 *   === Results using ExternalMergeSort (MappedByteBuffer) ===
 *   Duration:           X,XXX ms
 *   Peak heap used:     X.XX GB
 *   Max heap:           X.XX GB
 *   Heap ratio:         XX.X%
 *   File size:          X.XX GB
 *   Memory/file ratio:  X.Xx
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

        Path  inputPath = Paths.get(args[0]);
        int   col1      = Integer.parseInt(args[1]);
        int   col2      = Integer.parseInt(args[2]);
        long  chunkMB   = args.length > 3 ? Long.parseLong(args[3]) : 256L;

        if (!Files.exists(inputPath)) {
            System.err.println("File not found: " + inputPath);
            System.exit(1);
        }

        String inputName  = inputPath.getFileName().toString();
        String stem       = inputName.endsWith(".csv")
                            ? inputName.substring(0, inputName.length() - 4) : inputName;
        Path   outputPath = inputPath.resolveSibling(stem + "_sorted.csv");
        long   inputBytes = Files.size(inputPath);

        // ---- Read header ---------------------------------------------------
        String[] header;
        try (BufferedReader br = Files.newBufferedReader(inputPath)) {
            String line = br.readLine();
            if (line == null) { System.err.println("Empty file."); System.exit(1); }
            header = parseCsvLine(line);
        }

        System.out.println("=== CSV Sort Demo ===");
        System.out.println("Input  : " + inputPath + "  (" + toMB(inputBytes) + " MB)");
        System.out.println("Output : " + outputPath);
        System.out.printf ("Columns: %d total%n", header.length);
        for (int i = 0; i < header.length; i++) {
            System.out.printf("  [%d] %s%s%n", i, header[i],
                    i == col1 ? "  <- primary key" : (i == col2 ? "  <- secondary key" : ""));
        }
        System.out.printf("Chunk  : %d MB%n%n", chunkMB);

        // ---- Build comparator: col1 ASC then col2 ASC (nulls last) ---------
        Comparator<String> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<Row> cmp = Comparator
                .comparing((Row r) -> r.getString(col1), nullsLast)
                .thenComparing((Row r) -> r.getString(col2), nullsLast);

        SortConfig config = SortConfig.builder()
                .comparator(cmp)
                .chunkSizeBytes(chunkMB * 1024L * 1024L)
                .tempDir(Paths.get(System.getProperty("java.io.tmpdir")))
                .build();

        // ---- Start peak-heap monitor, then sort ----------------------------
        PeakHeapMonitor monitor = new PeakHeapMonitor();
        monitor.start();
        long startMs = System.currentTimeMillis();

        Iterator<Row> csvRows = csvIterator(inputPath);
        Iterator<Row> sorted  = new ExternalMergeSort(config).sort(csvRows);

        long rowCount = 0;
        try (BufferedWriter bw = Files.newBufferedWriter(outputPath)) {
            bw.write(joinCsvLine(header));
            bw.newLine();
            while (sorted.hasNext()) {
                bw.write(joinCsvLine(sorted.next().getValues()));
                bw.newLine();
                rowCount++;
            }
        }

        long elapsedMs = System.currentTimeMillis() - startMs;
        monitor.stop();

        // ---- Print comparison-format stats ---------------------------------
        long   peakHeap  = monitor.getPeakUsed();
        long   maxHeap   = monitor.getMaxHeap();
        double heapRatio = maxHeap > 0 ? (peakHeap * 100.0 / maxHeap) : 0;
        double memRatio  = inputBytes > 0 ? (double) peakHeap / inputBytes : 0;
        double throughput = elapsedMs > 0
                ? (inputBytes / (1024.0 * 1024.0)) / (elapsedMs / 1000.0) : 0;

        System.out.println("=== Results using ExternalMergeSort (MappedByteBuffer) ===");
        System.out.printf("Duration:           %,d ms%n",       elapsedMs);
        System.out.printf("Rows sorted:        %,d%n",          rowCount);
        System.out.printf("Peak heap used:     %s GB%n",        toGB(peakHeap));
        System.out.printf("Max heap:           %s GB%n",        toGB(maxHeap));
        System.out.printf("Heap ratio:         %.1f%%%n",       heapRatio);
        System.out.printf("File size:          %s GB%n",        toGB(inputBytes));
        System.out.printf("Memory/file ratio:  %.1fx%n",        memRatio);
        System.out.printf("Throughput:         %.1f MB/s%n%n",  throughput);

        System.out.println("=== JVM Heap Detail ===");
        System.out.printf("%-16s  %12s  %12s  %12s%n",
                "",              "Before sort", "Peak",        "After sort");
        System.out.printf("%-16s  %12s  %12s  %12s%n",
                "Heap used",
                toMB(monitor.getInitialUsed()) + " MB",
                toMB(peakHeap)                + " MB",
                toMB(monitor.getFinalUsed())   + " MB");
        System.out.printf("%-16s  %12s  %12s  %12s%n",
                "Heap max",
                toMB(maxHeap) + " MB",
                toMB(maxHeap) + " MB",
                toMB(maxHeap) + " MB");

        System.out.println();
        System.out.println("Sorted file: " + outputPath);
    }

    // -------------------------------------------------------------------------
    // Peak-heap monitor — samples MemoryMXBean every 50ms on a daemon thread
    // -------------------------------------------------------------------------

    private static final class PeakHeapMonitor {
        private final MemoryMXBean mx  = ManagementFactory.getMemoryMXBean();
        private final long initialUsed;
        private final long maxHeap;
        private volatile long peakUsed;
        private long finalUsed;
        private Thread thread;

        PeakHeapMonitor() {
            System.gc();
            var h       = mx.getHeapMemoryUsage();
            initialUsed = h.getUsed();
            maxHeap     = h.getMax();
            peakUsed    = initialUsed;
        }

        void start() {
            thread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    long used = mx.getHeapMemoryUsage().getUsed();
                    if (used > peakUsed) peakUsed = used;
                    try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                }
            }, "peak-heap-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() {
            thread.interrupt();
            try { thread.join(1000); } catch (InterruptedException ignored) {}
            System.gc();
            finalUsed = mx.getHeapMemoryUsage().getUsed();
        }

        long getInitialUsed() { return initialUsed; }
        long getPeakUsed()    { return peakUsed;    }
        long getFinalUsed()   { return finalUsed;   }
        long getMaxHeap()     { return maxHeap;     }
    }

    // -------------------------------------------------------------------------
    // CSV streaming iterator — reads data rows lazily, one line at a time
    // -------------------------------------------------------------------------

    private static Iterator<Row> csvIterator(Path path) throws IOException {
        BufferedReader br = Files.newBufferedReader(path);
        br.readLine(); // skip header

        return new Iterator<Row>() {
            private String nextLine = readNext();
            private boolean closed  = false;

            private String readNext() {
                try {
                    String line = br.readLine();
                    if (line == null && !closed) {
                        closed = true;
                        br.close();
                    }
                    return line;
                } catch (IOException e) {
                    throw new RuntimeException("Error reading CSV: " + e.getMessage(), e);
                }
            }

            @Override public boolean hasNext() { return nextLine != null; }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                Row row  = new Row(parseCsvLine(nextLine));
                nextLine = readNext();
                return row;
            }
        };
    }

    // -------------------------------------------------------------------------
    // CSV parsing — RFC 4180: handles quoted fields with embedded commas
    // -------------------------------------------------------------------------

    static String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder sb    = new StringBuilder();
        boolean inQuotes    = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"'); i++;
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

    private static String joinCsvLine(String[] values) {
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

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private static String toMB(long bytes) {
        return String.format("%,d", bytes / (1024 * 1024));
    }

    private static String toGB(long bytes) {
        return String.format("%.2f", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
