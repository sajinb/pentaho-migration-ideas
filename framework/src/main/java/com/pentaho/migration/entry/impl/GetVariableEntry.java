package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Reads a system property / environment variable into the job context. */
public class GetVariableEntry implements JobEntry {
    private String variableName, contextKey;
    @Override public void configure(Map<String, String> params) {
        variableName = params.get("variableName");
        contextKey   = params.getOrDefault("contextKey", variableName);
    }
    @Override public boolean execute(Map<String, String> context) {
        String value = System.getProperty(variableName, System.getenv().getOrDefault(variableName, ""));
        context.put(contextKey, value);
        return true;
    }
}
