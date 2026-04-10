package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Applies a JavaScript (ES5) script to each row using Mozilla Rhino.
 * Equivalent to Pentaho's ScriptValueMod (ModifiedJavaScriptValue) step.
 *
 * <p>The script receives one variable per input field (named by {@code fieldNames}).
 * After execution, output field values are read back from the scope.
 * New output fields are appended to the row; in-place updates overwrite existing values.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code script}       — JavaScript source to evaluate (required)</li>
 *   <li>{@code fieldNames}   — comma-separated input field names (injected by KtrParser)</li>
 *   <li>{@code outputFields} — comma-separated output field names (new or existing)</li>
 *   <li>{@code outputRename} — comma-separated rename targets corresponding to outputFields
 *                              (empty entry keeps the original name)</li>
 * </ul>
 *
 * <p>Variable binding: each input field is exposed as a JavaScript variable using the
 * field name with non-alphanumeric characters replaced by {@code _}. For example,
 * {@code "order id"} becomes {@code order_id} in the script scope.
 */
public class ScriptValueModStep extends AbstractStreamingStep {

    private String   scriptSource;
    private String[] inputFieldNames;   // names of all upstream fields
    private String[] inputVarNames;     // JS-safe variable names for each input field
    private String[] outputFields;      // names of fields written by the script (may be new)
    private String[] outputVarNames;    // JS-safe variable names for output fields
    private String[] outputFinalNames;  // final field names after any rename

    // compiled script (one Context per thread; Script is thread-safe once compiled)
    private Script compiledScript;

    @Override
    public void configure(Map<String, String> params) {
        scriptSource = params.get("script");
        if (scriptSource == null || scriptSource.isBlank())
            throw new IllegalArgumentException("ScriptValueModStep: 'script' param is required.");

        String namesRaw = params.get("fieldNames");
        if (namesRaw != null && !namesRaw.isBlank()) {
            inputFieldNames = namesRaw.split(",", -1);
        } else {
            inputFieldNames = new String[0];
        }
        inputVarNames = new String[inputFieldNames.length];
        for (int i = 0; i < inputFieldNames.length; i++) {
            inputVarNames[i] = toJsName(inputFieldNames[i]);
        }

        String outRaw = params.get("outputFields");
        if (outRaw != null && !outRaw.isBlank()) {
            outputFields = outRaw.split(",", -1);
        } else {
            outputFields = new String[0];
        }

        String renameRaw = params.get("outputRename");
        String[] renames = (renameRaw != null && !renameRaw.isBlank())
                ? renameRaw.split(",", -1) : new String[0];

        outputVarNames  = new String[outputFields.length];
        outputFinalNames = new String[outputFields.length];
        for (int i = 0; i < outputFields.length; i++) {
            outputVarNames[i] = toJsName(outputFields[i]);
            outputFinalNames[i] = (i < renames.length && !renames[i].isBlank())
                    ? renames[i].trim() : outputFields[i].trim();
        }

        // Compile once for reuse across rows
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1); // interpreted mode — safe for all scripts
            compiledScript = cx.compileString(scriptSource, "<script>", 1, null);
        } finally {
            Context.exit();
        }
    }

    @Override
    protected Row transform(Row row) {
        String[] oldValues = row.getValues();

        Context cx = Context.enter();
        try {
            Scriptable scope = cx.initStandardObjects();

            // Expose input fields as JS variables
            for (int i = 0; i < inputFieldNames.length && i < oldValues.length; i++) {
                String val = oldValues[i];
                Object jsVal = (val == null) ? Context.getUndefinedValue()
                                             : Context.javaToJS(val, scope);
                ScriptableObject.putProperty(scope, inputVarNames[i], jsVal);
            }

            compiledScript.exec(cx, scope);

            // Determine which output fields are new vs. updates to existing
            List<String> existingNames = new ArrayList<>();
            if (inputFieldNames.length > 0) {
                for (String n : inputFieldNames) existingNames.add(n.trim());
            }

            // Build updated values array
            String[] updated = oldValues.clone();
            List<String> newValues = new ArrayList<>();
            List<String> newFieldNamesList = new ArrayList<>();

            for (int i = 0; i < outputFields.length; i++) {
                String fieldName = outputFields[i].trim();
                String varName   = outputVarNames[i];
                String finalName = outputFinalNames[i];

                Object jsResult = ScriptableObject.getProperty(scope, varName);
                String result   = jsResultToString(jsResult);

                int existingIdx = findIndex(existingNames, fieldName);
                if (existingIdx >= 0 && existingIdx < updated.length) {
                    // In-place update
                    updated[existingIdx] = result;
                    // If renamed, we need to handle this by appending
                    if (!finalName.equals(fieldName)) {
                        newValues.add(result);
                        newFieldNamesList.add(finalName);
                    }
                } else {
                    // New field — append
                    newValues.add(result);
                    newFieldNamesList.add(finalName);
                }
            }

            if (newValues.isEmpty()) {
                return new Row(updated);
            }

            String[] combined = new String[updated.length + newValues.size()];
            System.arraycopy(updated, 0, combined, 0, updated.length);
            for (int i = 0; i < newValues.size(); i++) combined[updated.length + i] = newValues.get(i);
            return new Row(combined);

        } finally {
            Context.exit();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Converts a Pentaho field name to a JavaScript-safe variable name. */
    private static String toJsName(String name) {
        if (name == null) return "_";
        return name.trim().replaceAll("[^A-Za-z0-9_$]", "_");
    }

    private static String jsResultToString(Object jsResult) {
        if (jsResult == null
                || jsResult == Scriptable.NOT_FOUND
                || jsResult == Context.getUndefinedValue()) {
            return null;
        }
        return Context.toString(jsResult);
    }

    private static int findIndex(List<String> names, String name) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}
