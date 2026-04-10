package com.pentaho.migration.entry;

import com.pentaho.migration.entry.impl.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Registry mapping Pentaho job entry type names to Java classes.
 */
public final class JobEntryRegistry {

    private final Map<String, Class<? extends JobEntry>> registry = new HashMap<>();
    /** Factory functions take precedence over class-based instantiation. */
    private final Map<String, java.util.function.Function<Map<String, String>, JobEntry>> factories
            = new HashMap<>();

    public void register(String typeName, Class<? extends JobEntry> clazz) {
        registry.put(typeName, clazz);
    }

    /**
     * Registers a factory function for {@code typeName}.
     * The function receives the entry's params map and returns a fully-configured {@link JobEntry}.
     * Takes precedence over class registrations. Useful for testing and custom wiring.
     */
    public void registerFactory(String typeName,
                                java.util.function.Function<Map<String, String>, JobEntry> factory) {
        factories.put(typeName, factory);
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
        // Factory takes priority over class-based registration
        var factory = factories.get(typeName);
        if (factory != null) return factory.apply(params != null ? params : Map.of());

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
