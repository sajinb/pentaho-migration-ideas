package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Emits a single row with system information fields.
 * Equivalent to Pentaho's SystemInfo step.
 *
 * <p>Emitted fields (in order): [date, time, hostname, jvmVersion, osName, osVersion]
 */
public class SystemInfoStep extends AbstractSourceStep {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    protected Iterator<Row> readRows() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        String hostname;
        try {
            hostname = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        Row row = new Row(new String[]{
            now.format(DATE_FMT),
            now.format(TIME_FMT),
            hostname,
            System.getProperty("java.version"),
            System.getProperty("os.name"),
            System.getProperty("os.version")
        });
        return Collections.singletonList(row).iterator();
    }
}
