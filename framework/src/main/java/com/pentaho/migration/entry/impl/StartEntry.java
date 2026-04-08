package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** KJB START entry — always succeeds. Equivalent to Pentaho's Start entry. */
public class StartEntry implements JobEntry {
    @Override
    public boolean execute(Map<String, String> context) { return true; }
}
