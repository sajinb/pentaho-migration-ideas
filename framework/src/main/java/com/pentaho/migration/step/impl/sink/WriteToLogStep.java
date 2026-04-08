package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logs each row to java.util.logging and passes it unchanged downstream.
 * Acts as a pass-through (not a pure sink). Equivalent to Pentaho's WriteToLog step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code logLevel}   — "INFO" (default), "WARNING", "FINE", etc.
 *   <li>{@code logSubject} — prefix string for each log line
 * </ul>
 */
public class WriteToLogStep extends AbstractStreamingStep {

    private static final Logger LOG = Logger.getLogger(WriteToLogStep.class.getName());

    private Level  level      = Level.INFO;
    private String logSubject = "";

    @Override
    public void configure(Map<String, String> params) {
        String lvl = params.getOrDefault("logLevel", "INFO");
        try { level = Level.parse(lvl); } catch (IllegalArgumentException ignored) {}
        logSubject = params.getOrDefault("logSubject", "");
    }

    @Override
    protected Row transform(Row row) {
        LOG.log(level, logSubject + Arrays.toString(row.getValues()));
        return row;
    }
}
