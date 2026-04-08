package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Sets a job context variable to a constant value. */
public class SetVariableEntry implements JobEntry {
    private String variableName, variableValue;
    @Override public void configure(Map<String, String> params) {
        variableName  = params.get("variableName");
        variableValue = params.getOrDefault("variableValue", "");
    }
    @Override public boolean execute(Map<String, String> context) {
        context.put(variableName, variableValue);
        return true;
    }
}
