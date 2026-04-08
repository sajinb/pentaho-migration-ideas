package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;
import java.util.logging.Logger;

/** Writes a message to the log. Always succeeds. */
public class WriteToLogEntry implements JobEntry {
    private static final Logger LOG = Logger.getLogger(WriteToLogEntry.class.getName());
    private String message = "";
    @Override public void configure(Map<String, String> params) { message = params.getOrDefault("message", ""); }
    @Override public boolean execute(Map<String, String> context) { LOG.info(message); return true; }
}
