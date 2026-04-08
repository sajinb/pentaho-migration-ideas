package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * Reads entries from a Java .properties file and emits one row per property.
 * Each row has two fields: [key, value]. Equivalent to Pentaho's PropertyInput step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath} — path to the .properties file (required)
 * </ul>
 */
public class PropertyInputStep extends AbstractSourceStep {

    private String filePath;

    @Override
    public void configure(Map<String, String> params) {
        filePath = params.get("filePath");
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(Paths.get(filePath))) {
            props.load(is);
        }
        List<Row> rows = new ArrayList<>();
        for (String key : props.stringPropertyNames()) {
            rows.add(new Row(new String[]{key, props.getProperty(key)}));
        }
        return rows.iterator();
    }
}
