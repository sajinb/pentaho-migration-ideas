package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps one field value to another using a lookup table.
 * Equivalent to Pentaho's ValueMapper step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}         — 0-based column index to read and replace
 *   <li>{@code from.0}, {@code to.0}, … — mapping pairs
 *   <li>{@code defaultValue}   — value when no match found (default: original value)
 * </ul>
 */
public class ValueMapperStep extends AbstractStreamingStep {

    private int    column;
    private String defaultValue;
    private boolean useDefault;
    private final Map<String, String> mappings = new HashMap<>();

    @Override
    public void configure(Map<String, String> params) {
        column       = Integer.parseInt(params.get("column"));
        useDefault   = params.containsKey("defaultValue");
        defaultValue = params.getOrDefault("defaultValue", null);
        int i = 0;
        while (params.containsKey("from." + i)) {
            mappings.put(params.get("from." + i), params.get("to." + i));
            i++;
        }
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length) {
            String original = values[column];
            if (mappings.containsKey(original)) {
                values[column] = mappings.get(original);
            } else if (useDefault) {
                values[column] = defaultValue;
            }
        }
        return new Row(values);
    }
}
