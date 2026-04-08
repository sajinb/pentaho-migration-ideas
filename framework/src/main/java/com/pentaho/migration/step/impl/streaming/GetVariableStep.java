package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.HashMap;
import java.util.Map;

/**
 * Appends a new field populated from a named variable (environment variable or job variable).
 * Equivalent to Pentaho's GetVariable step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code variableName} — name of the variable to read
 *   <li>{@code defaultValue} — value to use if variable is not set
 * </ul>
 */
public class GetVariableStep extends AbstractStreamingStep {

    private String variableName;
    private String defaultValue = "";
    private Map<String, String> sharedVariables = new HashMap<>();

    public void setVariables(Map<String, String> vars) { this.sharedVariables = vars; }

    @Override
    public void configure(Map<String, String> params) {
        variableName = params.get("variableName");
        defaultValue = params.getOrDefault("defaultValue", "");
    }

    @Override
    protected Row transform(Row row) {
        String value = sharedVariables.getOrDefault(variableName,
                System.getProperty(variableName, System.getenv().getOrDefault(variableName, defaultValue)));
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = value;
        return new Row(nw);
    }
}
