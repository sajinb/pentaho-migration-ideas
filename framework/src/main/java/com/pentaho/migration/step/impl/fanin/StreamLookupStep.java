package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Hash-based stream lookup join. Equivalent to Pentaho's StreamLookup step.
 *
 * <p>Takes two inputs:
 * <ul>
 *   <li><b>Main stream</b> ({@code input[mainInputIndex]}) — streamed through one row at a time</li>
 *   <li><b>Lookup stream</b> ({@code input[lookupInputIndex]}) — fully buffered into a hash map</li>
 * </ul>
 *
 * <p>For each main-stream row the lookup hash map is probed by the join key. Matching lookup fields
 * are appended to the output row. Non-matching rows are still emitted but with {@code null} values
 * for the lookup fields (LEFT JOIN semantics).
 *
 * <p>Params (all resolved to 0-based indices by {@code KtrParser}):
 * <ul>
 *   <li>{@code lookupInputIndex} — which of the two inputs is the lookup stream (default 1)</li>
 *   <li>{@code keyStreamCols}   — comma-separated column indices in the main stream for the join key</li>
 *   <li>{@code keyLookupCols}   — comma-separated column indices in the lookup stream for the join key</li>
 *   <li>{@code valueFieldCols}  — comma-separated column indices in the lookup stream to append</li>
 * </ul>
 */
public class StreamLookupStep implements Step {

    private int   lookupInputIndex = 1;
    private int[] keyStreamCols;
    private int[] keyLookupCols;
    private int[] valueFieldCols;

    @Override
    public void configure(Map<String, String> params) {
        String li = params.get("lookupInputIndex");
        if (li != null) lookupInputIndex = Integer.parseInt(li);

        keyStreamCols  = parseIndices(params.get("keyStreamCols"));
        keyLookupCols  = parseIndices(params.get("keyLookupCols"));
        valueFieldCols = parseIndices(params.get("valueFieldCols"));
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        int mainInputIndex = lookupInputIndex == 0 ? 1 : 0;

        Iterator<Row> lookupIter = inputs.get(lookupInputIndex);
        Iterator<Row> mainIter   = inputs.get(mainInputIndex);

        // Build hash map: composite join key → first matching lookup row
        Map<String, Row> lookupMap = new HashMap<>();
        while (lookupIter.hasNext()) {
            Row row = lookupIter.next();
            String key = buildKey(row, keyLookupCols);
            lookupMap.putIfAbsent(key, row);
        }

        final int valueCount = valueFieldCols != null ? valueFieldCols.length : 0;

        return new Iterator<Row>() {
            @Override public boolean hasNext() { return mainIter.hasNext(); }

            @Override public Row next() {
                Row mainRow   = mainIter.next();
                String key    = buildKey(mainRow, keyStreamCols);
                Row lookupRow = lookupMap.get(key);
                return mergeRows(mainRow, lookupRow, valueCount);
            }
        };
    }

    private Row mergeRows(Row main, Row lookup, int valueCount) {
        int mainLen   = main.fieldCount();
        String[] out  = Arrays.copyOf(main.getValues(), mainLen + valueCount);
        if (lookup != null && valueFieldCols != null) {
            for (int i = 0; i < valueFieldCols.length; i++) {
                int col = valueFieldCols[i];
                out[mainLen + i] = col < lookup.fieldCount() ? lookup.getString(col) : null;
            }
        }
        // Slots for non-matching rows are already null from Arrays.copyOf
        return new Row(out);
    }

    /** Builds a composite join key string from the specified columns of a row. */
    private static String buildKey(Row row, int[] cols) {
        if (cols == null || cols.length == 0) return "";
        if (cols.length == 1) {
            int col = cols[0];
            String v = col < row.fieldCount() ? row.getString(col) : null;
            return v != null ? v : "\0";
        }
        StringBuilder sb = new StringBuilder();
        for (int col : cols) {
            String v = col < row.fieldCount() ? row.getString(col) : null;
            sb.append(v != null ? v : "\0").append('\0');
        }
        return sb.toString();
    }

    private static int[] parseIndices(String s) {
        if (s == null || s.isBlank()) return new int[0];
        String[] parts = s.split(",");
        int[] arr = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { arr[i] = Integer.parseInt(parts[i].trim()); }
            catch (NumberFormatException e) { arr[i] = 0; }
        }
        return arr;
    }
}
