package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Copies the value from one field to another.
 * Equivalent to Pentaho's SetValueField step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code sourceColumn} — 0-based index of the source field
 *   <li>{@code targetColumn} — 0-based index of the target field
 * </ul>
 */
public class SetValueFieldStep extends AbstractStreamingStep {

    private int sourceColumn;
    private int targetColumn;

    @Override
    public void configure(Map<String, String> params) {
        sourceColumn = Integer.parseInt(params.get("sourceColumn"));
        targetColumn = Integer.parseInt(params.get("targetColumn"));
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (sourceColumn < values.length && targetColumn < values.length) {
            values[targetColumn] = values[sourceColumn];
        }
        return new Row(values);
    }
}
