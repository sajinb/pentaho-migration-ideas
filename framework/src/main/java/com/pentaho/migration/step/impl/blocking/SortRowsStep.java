package com.pentaho.migration.step.impl.blocking;

import com.pentaho.migration.sort.*;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Sorts rows by one or more columns using ExternalMergeSort (MappedByteBuffer).
 * Handles datasets larger than heap without OOM. Equivalent to Pentaho's SortRows step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code columns}     — comma-separated 0-based column indices (primary first)
 *   <li>{@code ascending}   — "true" (default) or "false" per column, comma-separated
 *   <li>{@code chunkSizeMb} — memory per sort chunk in MB (default 256)
 * </ul>
 */
public class SortRowsStep implements Step {

    private int[]     columns;
    private boolean[] ascending;
    private long      chunkSizeBytes = 256L * 1024 * 1024;

    @Override
    public void configure(Map<String, String> params) {
        String[] colParts = params.get("columns").split(",");
        columns   = new int[colParts.length];
        ascending = new boolean[colParts.length];
        Arrays.fill(ascending, true);

        for (int i = 0; i < colParts.length; i++) columns[i] = Integer.parseInt(colParts[i].trim());

        if (params.containsKey("ascending")) {
            String[] ascParts = params.get("ascending").split(",");
            for (int i = 0; i < Math.min(ascParts.length, ascending.length); i++) {
                ascending[i] = !"false".equalsIgnoreCase(ascParts[i].trim());
            }
        }
        if (params.containsKey("chunkSizeMb")) {
            chunkSizeBytes = Long.parseLong(params.get("chunkSizeMb")) * 1024L * 1024;
        }
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        Comparator<Row> cmp = buildComparator();
        SortConfig config = SortConfig.builder()
                .comparator(cmp)
                .chunkSizeBytes(chunkSizeBytes)
                .tempDir(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir")))
                .build();
        return new ExternalMergeSort(config).sort(inputs.get(0));
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
