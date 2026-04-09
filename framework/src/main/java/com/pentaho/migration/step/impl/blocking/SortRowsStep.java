package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.*;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Sorts rows by one or more columns.
 *
 * <p>Strategy:
 * <ul>
 *   <li>If the estimated in-memory size of all rows is <b>below {@code thresholdMb}</b>
 *       (default 2 048 MB = 2 GB), rows are sorted in-memory with {@link List#sort}.
 *   <li>Otherwise, {@link ExternalMergeSort} (MappedByteBuffer, disk-backed) is used so
 *       that datasets larger than heap are handled without OOM.
 * </ul>
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns}      — comma-separated 0-based column indices (primary first)
 *   <li>{@code ascending}    — "true" (default) or "false" per column, comma-separated
 *   <li>{@code thresholdMb}  — switch-to-disk threshold in MB (default 2048)
 *   <li>{@code chunkSizeMb}  — memory per external-sort chunk in MB (default 256)
 * </ul>
 */
public class SortRowsStep implements Step {

    private static final long DEFAULT_THRESHOLD_BYTES = 2048L * 1024 * 1024; // 2 GB

    private int[]     columns;
    private boolean[] ascending;
    private long      thresholdBytes = DEFAULT_THRESHOLD_BYTES;
    private long      chunkSizeBytes = 256L * 1024 * 1024;

    @Override
    public void configure(Map<String, String> params) {
        String[] colParts = params.get("columns").split(",");
        columns   = new int[colParts.length];
        ascending = new boolean[colParts.length];
        Arrays.fill(ascending, true);

        for (int i = 0; i < colParts.length; i++) {
            String token = colParts[i].trim();
            try {
                columns[i] = Integer.parseInt(token);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                    "SortRows: column '" + token + "' is not a 0-based integer index. " +
                    "Add a <fields> section to the upstream CsvInput/TextFileInput step in " +
                    "your KTR so the converter can resolve column names to numeric indices, " +
                    "then re-convert.");
            }
        }

        if (params.containsKey("ascending")) {
            String[] ascParts = params.get("ascending").split(",");
            for (int i = 0; i < Math.min(ascParts.length, ascending.length); i++) {
                ascending[i] = !"false".equalsIgnoreCase(ascParts[i].trim());
            }
        }
        if (params.containsKey("thresholdMb")) {
            thresholdBytes = Long.parseLong(params.get("thresholdMb")) * 1024L * 1024;
        }
        if (params.containsKey("chunkSizeMb")) {
            chunkSizeBytes = Long.parseLong(params.get("chunkSizeMb")) * 1024L * 1024;
        }
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        // Accumulate all rows (blocking step — must see every row before emitting any).
        List<Row> rows = new ArrayList<>();
        long estimatedBytes = 0;
        Iterator<Row> upstream = inputs.get(0);
        while (upstream.hasNext()) {
            Row row = upstream.next();
            rows.add(row);
            estimatedBytes += estimateRowBytes(row);
        }

        Comparator<Row> cmp = buildComparator();

        if (estimatedBytes < thresholdBytes) {
            // Small dataset — sort in-memory, no disk I/O.
            rows.sort(cmp);
            return rows.iterator();
        }

        // Large dataset (>= threshold) — external merge sort spills to disk.
        SortConfig config = SortConfig.builder()
                .comparator(cmp)
                .chunkSizeBytes(chunkSizeBytes)
                .tempDir(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))
                .build();
        return new ExternalMergeSort(config).sort(rows.iterator());
    }

    /**
     * Estimates heap bytes for one row: JVM object overhead + String object overhead
     * per non-null field + 2 bytes per character.
     */
    private static long estimateRowBytes(Row row) {
        long bytes = 64; // Row object overhead + values array header
        for (int i = 0; i < row.fieldCount(); i++) {
            String s = row.getString(i);
            if (s != null) bytes += 48 + s.length() * 2L; // String obj + char storage
        }
        return bytes;
    }

    private Comparator<Row> buildComparator() {
        Comparator<String> natural = Comparator.nullsLast(Comparator.naturalOrder());
        Comparator<Row> cmp = null;
        for (int i = 0; i < columns.length; i++) {
            final int    col = columns[i];
            final boolean asc = ascending[i];
            Comparator<Row> part = Comparator.comparing(r -> r.getString(col), natural);
            if (!asc) part = part.reversed();
            cmp = (cmp == null) ? part : cmp.thenComparing(part);
        }
        return cmp != null ? cmp : Comparator.comparing(r -> "");
    }
}
