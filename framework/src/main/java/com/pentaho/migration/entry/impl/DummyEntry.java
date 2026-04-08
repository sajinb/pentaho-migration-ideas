package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** No-op job entry. Equivalent to Pentaho's Dummy job entry. */
public class DummyEntry implements JobEntry {
    @Override public boolean execute(Map<String, String> context) { return true; }
}
