package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

/**
 * Computes a checksum over all (or selected) fields and appends it as a new field.
 * Equivalent to Pentaho's CheckSum step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code algorithm} — "MD5", "SHA-1", "SHA-256", "CRC32" (default: "MD5")
 *   <li>{@code columns}   — comma-separated 0-based column indices; omit for all columns
 * </ul>
 */
public class CheckSumStep extends AbstractStreamingStep {

    private String algorithm = "MD5";
    private int[]  columns   = null;

    @Override
    public void configure(Map<String, String> params) {
        algorithm = params.getOrDefault("algorithm", "MD5");
        if (params.containsKey("columns")) {
            String[] parts = params.get("columns").split(",");
            columns = new int[parts.length];
            for (int i = 0; i < parts.length; i++) columns[i] = Integer.parseInt(parts[i].trim());
        }
    }

    @Override
    protected Row transform(Row row) {
        String checksum;
        try {
            MessageDigest md  = MessageDigest.getInstance(algorithm);
            int[]         cols = (columns != null) ? columns
                    : java.util.stream.IntStream.range(0, row.fieldCount()).toArray();
            for (int c : cols) {
                String v = c < row.fieldCount() ? row.getString(c) : null;
                if (v != null) md.update(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            checksum = HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            checksum = null;
        }
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = checksum;
        return new Row(nw);
    }
}
