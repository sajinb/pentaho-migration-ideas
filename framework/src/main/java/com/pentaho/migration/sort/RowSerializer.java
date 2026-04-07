package com.pentaho.migration.sort;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Serializes and deserializes Row objects to/from ByteBuffer.
 *
 * Wire format per row:
 *   [2 bytes: field count (short)]
 *   per field:
 *     [4 bytes: UTF-8 byte length, or -1 for null]
 *     [N bytes: UTF-8 encoded field value]
 *
 * File header (written once at the start of each TempChunkFile):
 *   [8 bytes: row count (long)]
 */
public final class RowSerializer {

    private RowSerializer() {}

    /**
     * Returns the number of bytes that write() will consume in the ByteBuffer.
     */
    public static int serializedSize(Row row) {
        int size = 2; // field count (short)
        for (int i = 0; i < row.fieldCount(); i++) {
            size += 4; // length prefix (int)
            String value = row.getString(i);
            if (value != null) {
                size += value.getBytes(StandardCharsets.UTF_8).length;
            }
        }
        return size;
    }

    /**
     * Writes a row into buf at the current position. buf must have at least
     * serializedSize(row) bytes of remaining capacity.
     */
    public static void write(ByteBuffer buf, Row row) {
        buf.putShort((short) row.fieldCount());
        for (int i = 0; i < row.fieldCount(); i++) {
            String value = row.getString(i);
            if (value == null) {
                buf.putInt(-1);
            } else {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                buf.putInt(bytes.length);
                buf.put(bytes);
            }
        }
    }

    /**
     * Reads one row from buf at the current position.
     */
    public static Row read(ByteBuffer buf) {
        int fieldCount = buf.getShort() & 0xFFFF;
        String[] values = new String[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            int len = buf.getInt();
            if (len == -1) {
                values[i] = null;
            } else {
                byte[] bytes = new byte[len];
                buf.get(bytes);
                values[i] = new String(bytes, StandardCharsets.UTF_8);
            }
        }
        return new Row(values);
    }
}
