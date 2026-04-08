package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evaluates a regex against a field and extracts capture groups as new fields.
 * Equivalent to Pentaho's RegexEval step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}  — 0-based column index to match against
 *   <li>{@code pattern} — Java regex pattern (capture groups become new fields)
 * </ul>
 *
 * <p>If the pattern has N capture groups, N new fields are appended (null if no match).
 */
public class RegexEvalStep extends AbstractStreamingStep {

    private int     column;
    private Pattern pattern;

    @Override
    public void configure(Map<String, String> params) {
        column  = Integer.parseInt(params.get("column"));
        pattern = Pattern.compile(params.get("pattern"));
    }

    @Override
    protected Row transform(Row row) {
        int     groupCount = pattern.matcher("").groupCount();
        String  input      = column < row.fieldCount() ? row.getString(column) : null;
        String[] groups    = new String[groupCount];

        if (input != null) {
            Matcher m = pattern.matcher(input);
            if (m.find()) {
                for (int i = 0; i < groupCount; i++) {
                    groups[i] = m.group(i + 1);
                }
            }
        }

        String[] old = row.getValues();
        String[] nw  = new String[old.length + groupCount];
        System.arraycopy(old, 0, nw, 0, old.length);
        System.arraycopy(groups, 0, nw, old.length, groupCount);
        return new Row(nw);
    }
}
