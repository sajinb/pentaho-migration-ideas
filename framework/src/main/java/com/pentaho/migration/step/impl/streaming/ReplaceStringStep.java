package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Replaces occurrences of a pattern in a field. Equivalent to Pentaho's ReplaceString step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}      — 0-based column index
 *   <li>{@code search}      — the string or regex to search for
 *   <li>{@code replacement} — the replacement string
 *   <li>{@code regex}       — "true" to treat search as a regex pattern; default "false"
 * </ul>
 */
public class ReplaceStringStep extends AbstractStreamingStep {

    private int     column;
    private String  search;
    private String  replacement;
    private boolean useRegex;
    private Pattern pattern;

    @Override
    public void configure(Map<String, String> params) {
        column      = Integer.parseInt(params.get("column"));
        search      = params.get("search");
        replacement = params.getOrDefault("replacement", "");
        useRegex    = "true".equalsIgnoreCase(params.getOrDefault("regex", "false"));
        if (useRegex) pattern = Pattern.compile(search);
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues().clone();
        if (column < values.length && values[column] != null) {
            if (useRegex) {
                values[column] = pattern.matcher(values[column]).replaceAll(replacement);
            } else {
                values[column] = values[column].replace(search, replacement);
            }
        }
        return new Row(values);
    }
}
