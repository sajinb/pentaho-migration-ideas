package com.pentaho.migration.entry.impl;

import com.pentaho.migration.entry.JobEntry;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/** Returns true if the specified file exists. */
public class FileExistsEntry implements JobEntry {
    private String filePath;
    @Override public void configure(Map<String, String> params) { filePath = params.get("filePath"); }
    @Override public boolean execute(Map<String, String> context) {
        return filePath != null && Files.exists(Paths.get(filePath));
    }
}
