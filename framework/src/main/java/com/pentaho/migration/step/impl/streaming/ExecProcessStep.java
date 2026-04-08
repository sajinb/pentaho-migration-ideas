package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * Executes an OS process using a command built from a row field, and appends
 * the stdout (first line) and exit code as new fields.
 * Equivalent to Pentaho's ExecProcess step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code commandColumn} — 0-based column index containing the command to run
 * </ul>
 */
public class ExecProcessStep extends AbstractStreamingStep {

    private int commandColumn;

    @Override
    public void configure(Map<String, String> params) {
        commandColumn = Integer.parseInt(params.getOrDefault("commandColumn", "0"));
    }

    @Override
    protected Row transform(Row row) {
        String command = commandColumn < row.fieldCount() ? row.getString(commandColumn) : null;
        String output  = null;
        String exitCode = null;
        if (command != null) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"/bin/sh", "-c", command});
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    output = br.readLine();
                }
                exitCode = String.valueOf(p.waitFor());
            } catch (Exception e) {
                exitCode = "-1";
                output   = e.getMessage();
            }
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 2];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length]     = output;
        nw[old.length + 1] = exitCode;
        return new Row(nw);
    }
}
