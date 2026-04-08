package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Appends one or more constant-value fields to each row.
 * Equivalent to Pentaho's Constant step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code value.0}, {@code value.1}, … — constant values to append
 * </ul>
 */
public class ConstantStep extends AbstractStreamingStep {

    private String[] constants;

    @Override
    public void configure(Map<String, String> params) {
        int count = 0;
        while (params.containsKey("value." + count)) count++;
        constants = new String[count];
        for (int i = 0; i < count; i++) constants[i] = params.get("value." + i);
    }

    @Override
    protected Row transform(Row row) {
        String[] old = row.getValues();
        String[] nw  = new String[old.length + constants.length];
        System.arraycopy(old, 0, nw, 0, old.length);
        System.arraycopy(constants, 0, nw, old.length, constants.length);
        return new Row(nw);
    }
}
