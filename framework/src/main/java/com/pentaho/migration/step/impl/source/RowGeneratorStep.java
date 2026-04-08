package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.util.*;

/**
 * Generates a fixed number of rows, each populated with constant field values.
 * Equivalent to Pentaho's RowGenerator step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code rowLimit} — number of rows to generate (default 1)
 *   <li>{@code field.N}  — field values as "field.0", "field.1", etc.
 * </ul>
 */
public class RowGeneratorStep extends AbstractSourceStep {

    private long rowLimit = 1;
    private String[] fieldValues = new String[0];

    @Override
    public void configure(Map<String, String> params) {
        rowLimit = Long.parseLong(params.getOrDefault("rowLimit", "1"));
        List<String> vals = new ArrayList<>();
        int i = 0;
        while (params.containsKey("field." + i)) {
            vals.add(params.get("field." + i));
            i++;
        }
        fieldValues = vals.toArray(new String[0]);
    }

    @Override
    protected Iterator<Row> readRows() {
        long limit = rowLimit;
        String[] values = fieldValues;
        return new Iterator<Row>() {
            long remaining = limit;
            @Override public boolean hasNext() { return remaining > 0; }
            @Override public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                remaining--;
                return new Row(values.clone());
            }
        };
    }
}
