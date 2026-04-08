package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Phase 3 stub: sends an email. Requires Jakarta Mail. */
public class MailEntry implements JobEntry {
    @Override public boolean execute(Map<String, String> context) {
        throw new UnsupportedOperationException("MailEntry requires Phase 3 (Jakarta Mail).");
    }
}
