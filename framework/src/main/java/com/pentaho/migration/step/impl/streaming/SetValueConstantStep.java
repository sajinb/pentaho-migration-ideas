package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Overwrites the value of a specific field with a constant.
 * Equivalent to Pentaho's SetValueConstant step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column} — 0-based column index to overwrite
 *   <li>{@code value}  — constant value to set (null to set null)
 * </ul>
 */
public class SetValueConstantStep extends AbstractStreamingStep {

    private int    column;
    private String value;

    @Override
    public void configure(Map<String, String> params) {
        column = Integer.parseInt(params.get("column"));
        value  = params.get("value");
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length) values[column] = value;
        return new Row(values);
    }
}
