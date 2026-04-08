package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.*;

/**
 * Computes the cartesian product of N input streams.
 * Equivalent to Pentaho's JoinRows step.
 *
 * <p>All streams are materialized in memory (caution with large datasets).
 * Output: concatenation of one row from each stream, for every combination.
 */
public class JoinRowsStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        List<List<Row>> sides = new ArrayList<>();
        for (Iterator<Row> it : inputs) {
            List<Row> rows = new ArrayList<>();
            while (it.hasNext()) rows.add(it.next());
            sides.add(rows);
        }
        List<Row> result = new ArrayList<>();
        cartesianProduct(sides, 0, new ArrayList<>(), result);
        return result.iterator();
    }

    private void cartesianProduct(List<List<Row>> sides, int depth,
                                   List<Row> current, List<Row> result) {
        if (depth == sides.size()) {
            // Merge current combination into one Row
            int totalFields = current.stream().mapToInt(Row::fieldCount).sum();
            String[] values = new String[totalFields];
            int idx = 0;
            for (Row r : current) {
                for (int i = 0; i < r.fieldCount(); i++) values[idx++] = r.getString(i);
            }
            result.add(new Row(values));
            return;
        }
        for (Row row : sides.get(depth)) {
            current.add(row);
            cartesianProduct(sides, depth + 1, current, result);
            current.remove(current.size() - 1);
        }
    }
}
