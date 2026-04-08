package com.pentaho.migration.step.impl.fanin;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Concatenates N input streams sequentially: exhausts inputs.get(0), then inputs.get(1), etc.
 * Equivalent to Pentaho's Append step.
 */
public class AppendStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        return new Iterator<Row>() {
            int idx = 0;

            private Iterator<Row> current() {
                while (idx < inputs.size() && !inputs.get(idx).hasNext()) idx++;
                return idx < inputs.size() ? inputs.get(idx) : null;
            }

            @Override public boolean hasNext() { return current() != null; }

            @Override public Row next() {
                Iterator<Row> cur = current();
                if (cur == null) throw new NoSuchElementException();
                return cur.next();
            }
        };
    }
}
