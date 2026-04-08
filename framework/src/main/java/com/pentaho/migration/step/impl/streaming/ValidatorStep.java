package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a field against a rule and appends a boolean "valid" field.
 * Equivalent to Pentaho's Validator step (Phase 1: single-field regex validation).
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}           — 0-based column index to validate
 *   <li>{@code allowNull}        — "true" to treat null as valid; default "false"
 *   <li>{@code pattern}          — optional regex the value must fully match
 *   <li>{@code minLength}        — optional minimum string length
 *   <li>{@code maxLength}        — optional maximum string length
 * </ul>
 */
public class ValidatorStep extends AbstractStreamingStep {

    private int     column;
    private boolean allowNull  = false;
    private Pattern pattern    = null;
    private int     minLength  = -1;
    private int     maxLength  = -1;

    @Override
    public void configure(Map<String, String> params) {
        column    = Integer.parseInt(params.getOrDefault("column", "0"));
        allowNull = "true".equalsIgnoreCase(params.getOrDefault("allowNull", "false"));
        if (params.containsKey("pattern"))   pattern   = Pattern.compile(params.get("pattern"));
        if (params.containsKey("minLength")) minLength = Integer.parseInt(params.get("minLength"));
        if (params.containsKey("maxLength")) maxLength = Integer.parseInt(params.get("maxLength"));
    }

    @Override
    protected Row transform(Row row) {
        String value = column < row.fieldCount() ? row.getString(column) : null;
        boolean valid;
        if (value == null) {
            valid = allowNull;
        } else {
            valid = true;
            if (minLength >= 0 && value.length() < minLength) valid = false;
            if (maxLength >= 0 && value.length() > maxLength) valid = false;
            if (pattern != null && !pattern.matcher(value).matches()) valid = false;
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = String.valueOf(valid);
        return new Row(nw);
    }
}
