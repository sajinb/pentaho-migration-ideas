package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Appends a boolean "isLast" field that is "true" only for the final row.
 * Equivalent to Pentaho's DetectLastRow step.
 *
 * <p>Requires one-row lookahead to detect the last row.
 */
public class DetectLastRowStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        return new Iterator<Row>() {
            Row next = upstream.hasNext() ? upstream.next() : null;

            @Override public boolean hasNext() { return next != null; }

            @Override public Row next() {
                if (next == null) throw new NoSuchElementException();
                Row current  = next;
                next         = upstream.hasNext() ? upstream.next() : null;
                boolean last = (next == null);
                String[] old = current.getValues();
                String[] nw  = new String[old.length + 1];
                System.arraycopy(old, 0, nw, 0, old.length);
                nw[old.length] = last ? "true" : "false";
                return new Row(nw);
            }
        };
    }
}
