package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Aborts the job by returning false (failure). Equivalent to Pentaho's Abort job entry. */
public class AbortEntry implements JobEntry {
    private String message = "Job aborted";
    @Override public void configure(Map<String, String> params) {
        message = params.getOrDefault("message", message);
    }
    @Override public boolean execute(Map<String, String> context) {
        System.err.println("[ABORT] " + message);
        return false;
    }
}
