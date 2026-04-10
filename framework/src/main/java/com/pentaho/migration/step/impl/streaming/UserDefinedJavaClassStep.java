package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import org.codehaus.janino.ScriptEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Executes inline Java code compiled at runtime using Janino.
 * Equivalent to Pentaho's UserDefinedJavaClass step.
 *
 * <p>The script body receives the current row's field values via {@code String[] fields}
 * and the corresponding field names via {@code String[] fieldNames}. It must return a
 * {@code String[]} representing the new row values (may be longer than the input if
 * new fields are appended).
 *
 * <p>Example script body:
 * <pre>{@code
 * String name = fields[0];
 * String upper = (name != null) ? name.toUpperCase() : null;
 * String[] result = new String[fields.length + 1];
 * System.arraycopy(fields, 0, result, 0, fields.length);
 * result[fields.length] = upper;
 * return result;
 * }</pre>
 *
 * <p>Params:
 * <ul>
 *   <li>{@code script}     — Java method body (required); must end with {@code return String[];}</li>
 *   <li>{@code fieldNames} — comma-separated input field names (injected by KtrParser)</li>
 * </ul>
 */
public class UserDefinedJavaClassStep extends AbstractStreamingStep {

    private String[] inputFieldNames;
    private CompiledScript compiledScript;

    /** Functional interface mirroring the compiled script signature. */
    @FunctionalInterface
    interface CompiledScript {
        String[] execute(String[] fields, String[] fieldNames) throws Exception;
    }

    @Override
    public void configure(Map<String, String> params) {
        String source = params.get("script");
        if (source == null || source.isBlank())
            throw new IllegalArgumentException("UserDefinedJavaClassStep: 'script' param is required.");

        String namesRaw = params.get("fieldNames");
        if (namesRaw != null && !namesRaw.isBlank()) {
            inputFieldNames = namesRaw.split(",", -1);
        } else {
            inputFieldNames = new String[0];
        }

        try {
            ScriptEvaluator se = new ScriptEvaluator();
            se.setReturnType(String[].class);
            se.setParameters(
                new String[]{ "fields", "fieldNames" },
                new Class<?>[]{ String[].class, String[].class }
            );
            se.cook(source);

            compiledScript = (fields, fieldNames) -> (String[]) se.evaluate(
                new Object[]{ fields, fieldNames }
            );
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "UserDefinedJavaClassStep: failed to compile script: " + e.getMessage(), e);
        }
    }

    @Override
    protected Row transform(Row row) {
        String[] values = row.getValues();
        try {
            String[] result = compiledScript.execute(values, inputFieldNames);
            return new Row(result != null ? result : values);
        } catch (Exception e) {
            throw new RuntimeException("UserDefinedJavaClassStep script execution failed: " + e.getMessage(), e);
        }
    }
}
