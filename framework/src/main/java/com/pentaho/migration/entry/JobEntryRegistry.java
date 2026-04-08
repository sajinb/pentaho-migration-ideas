package com.pentaho.migration.entry;

import com.pentaho.migration.entry.impl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping Pentaho job entry type names to Java classes.
 */
public final class JobEntryRegistry {

    private final Map<String, Class<? extends JobEntry>> registry = new HashMap<>();

    public void register(String typeName, Class<? extends JobEntry> clazz) {
        registry.put(typeName, clazz);
    }

    public static JobEntryRegistry withDefaults() {
        JobEntryRegistry r = new JobEntryRegistry();
        r.register("Start",              StartEntry.class);
        r.register("Success",            SuccessEntry.class);
        r.register("RunTransformation",  RunTransformationEntry.class);
        r.register("Dummy",              DummyEntry.class);
        r.register("Abort",              AbortEntry.class);
        r.register("SetVariable",        SetVariableEntry.class);
        r.register("GetVariable",        GetVariableEntry.class);
        r.register("ExecSQL",            ExecSQLEntry.class);
        r.register("ExecProcess",        ExecProcessEntry.class);
        r.register("FileExists",         FileExistsEntry.class);
        r.register("WriteToLog",         WriteToLogEntry.class);
        r.register("Mail",               MailEntry.class);
        return r;
    }

    public JobEntry instantiate(String typeName, Map<String, String> params) {
        Class<? extends JobEntry> clazz = registry.get(typeName);
        if (clazz == null) {
            throw new IllegalArgumentException("Unknown job entry type: '" + typeName + "'");
        }
        try {
            JobEntry entry = clazz.getDeclaredConstructor().newInstance();
            if (params != null) entry.configure(params);
            return entry;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to instantiate entry type '" + typeName + "'", e);
        }
    }
}
