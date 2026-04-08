package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Emits each input row N times. Equivalent to Pentaho's CloneRow step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code cloneCount} — number of times to repeat each row (default 1 = no-op)
 * </ul>
 */
public class CloneRowStep implements Step {

    private int cloneCount = 1;

    @Override
    public void configure(Map<String, String> params) {
        cloneCount = Integer.parseInt(params.getOrDefault("cloneCount", "1"));
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        final int count = cloneCount;
        return new Iterator<Row>() {
            Row   current    = null;
            int   remaining  = 0;

            private void advance() {
                while (remaining == 0 && upstream.hasNext()) {
                    current   = upstream.next();
                    remaining = count;
                }
            }

            @Override public boolean hasNext() { advance(); return remaining > 0; }

            @Override public Row next() {
                advance();
                if (remaining == 0) throw new NoSuchElementException();
                remaining--;
                return current;
            }
        };
    }
}
