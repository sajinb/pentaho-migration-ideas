package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** KJB SUCCESS entry — marks successful completion. Always returns true. */
public class SuccessEntry implements JobEntry {
    @Override
    public boolean execute(Map<String, String> context) { return true; }
}
