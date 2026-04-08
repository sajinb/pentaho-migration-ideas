package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Multi-way merge join of N pre-sorted streams (INNER join only in Phase 1).
 * Equivalent to Pentaho's MultiwayMergeJoin step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code keyColumn} — 0-based key column index (same in all streams)
 * </ul>
 *
 * <p>Materializes all inputs then performs a hash-based join.
 * Phase 1 implementation supports INNER join of N streams.
 */
public class MultiwayMergeJoinStep implements Step {

    private int keyColumn = 0;

    @Override
    public void configure(Map<String, String> params) {
        keyColumn = Integer.parseInt(params.getOrDefault("keyColumn", "0"));
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        if (inputs.isEmpty()) return Collections.emptyIterator();

        // Build hash maps for all streams except the first
        List<Map<String, List<Row>>> maps = new ArrayList<>();
        for (int i = 1; i < inputs.size(); i++) {
            Map<String, List<Row>> map = new LinkedHashMap<>();
            while (inputs.get(i).hasNext()) {
                Row r = inputs.get(i).next();
                String key = keyColumn < r.fieldCount() ? r.getString(keyColumn) : null;
                map.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
            }
            maps.add(map);
        }

        List<Row> result = new ArrayList<>();
        Iterator<Row> left = inputs.get(0);
        while (left.hasNext()) {
            Row lRow = left.next();
            String key = keyColumn < lRow.fieldCount() ? lRow.getString(keyColumn) : null;
            // Start with singleton list of the left row
            List<Row> combinations = List.of(lRow);
            for (Map<String, List<Row>> map : maps) {
                List<Row> rightRows = map.getOrDefault(key, List.of());
                if (rightRows.isEmpty()) { combinations = List.of(); break; }
                List<Row> next = new ArrayList<>();
                for (Row combo : combinations)
                    for (Row rRow : rightRows)
                        next.add(mergeRows(combo, rRow));
                combinations = next;
            }
            result.addAll(combinations);
        }
        return result.iterator();
    }

    private Row mergeRows(Row a, Row b) {
        String[] values = new String[a.fieldCount() + b.fieldCount()];
        for (int i = 0; i < a.fieldCount(); i++) values[i] = a.getString(i);
        for (int i = 0; i < b.fieldCount(); i++) values[a.fieldCount() + i] = b.getString(i);
        return new Row(values);
    }
}
