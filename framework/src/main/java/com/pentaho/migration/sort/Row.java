package com.pentaho.migration.sort;

import java.util.Arrays;

/**
 * Represents a single data row as a fixed-length array of String fields.
 * Null values are permitted and are preserved through serialization.
 */
public final class Row {

    private final String[] values;

    public Row(String[] values) {
        this.values = values;
    }

    public String getString(int index) {
        return values[index];
    }

    public int fieldCount() {
        return values.length;
    }

    public String[] getValues() {
        return values;
    }

    @Override
    public String toString() {
        return Arrays.toString(values);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Row)) return false;
        return Arrays.equals(values, ((Row) o).values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(values);
    }
}
