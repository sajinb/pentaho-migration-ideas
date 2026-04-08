package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Sets a transformation/job variable from the value of a field.
 * The variable is stored in the step's own params map so downstream steps can retrieve it.
 * Equivalent to Pentaho's SetVariable step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code column}       — 0-based column index to read the value from
 *   <li>{@code variableName} — name of the variable to set
 * </ul>
 */
public class SetVariableStep extends AbstractStreamingStep {

    private int    column;
    private String variableName;
    private final Map<String, String> variables;

    public SetVariableStep() {
        this.variables = new java.util.HashMap<>();
    }

    public SetVariableStep(Map<String, String> sharedVariables) {
        this.variables = sharedVariables;
    }

    @Override
    public void configure(Map<String, String> params) {
        column       = Integer.parseInt(params.get("column"));
        variableName = params.get("variableName");
    }

    @Override
    protected Row transform(Row row) {
        if (column < row.fieldCount()) {
            variables.put(variableName, row.getString(column));
        }
        return row;
    }

    /** Returns the current value of the named variable. */
    public String getVariable(String name) { return variables.get(name); }
}
