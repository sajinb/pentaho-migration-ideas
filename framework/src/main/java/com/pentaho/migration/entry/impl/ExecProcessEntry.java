package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.util.Map;

/** Executes an OS command. Returns true if exit code == 0. */
public class ExecProcessEntry implements JobEntry {
    private String command;
    @Override public void configure(Map<String, String> params) { command = params.get("command"); }
    @Override public boolean execute(Map<String, String> context) throws Exception {
        if (command == null) return false;
        Process p = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
        return p.waitFor() == 0;
    }
}
