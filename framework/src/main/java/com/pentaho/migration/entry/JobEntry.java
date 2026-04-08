package com.pentaho.migration.entry;

import java.util.Map;

/**
 * A single KJB entry in a job pipeline.
 * Implementations represent each of the 12 Pentaho job entry types.
 */
public interface JobEntry {

    /**
     * Configure this entry from the params map in the YAML definition.
     * Called once before {@link #execute(Map)}.
     */
    default void configure(Map<String, String> params) {}

    /**
     * Execute this entry.
     *
     * @param context job-level variable map (shared across all entries, readable and writable)
     * @return true if the entry succeeded; false on failure
     */
    boolean execute(Map<String, String> context) throws Exception;
}
