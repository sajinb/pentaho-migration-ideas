package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * Detects whether the upstream stream is empty and records the result.
 * Equivalent to Pentaho's DetectEmptyStream step.
 *
 * <p>If the stream is non-empty, rows are passed through to downstream.
 * If the stream is empty, the step fires (reports empty=true) and emits nothing.
 */
public class DetectEmptyStreamStep implements Step {

    private boolean empty = true;

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) {
        Iterator<Row> upstream = inputs.get(0);
        if (!upstream.hasNext()) {
            empty = true;
            return Collections.emptyIterator();
        }
        empty = false;
        return upstream;
    }

    /** Returns true if the upstream stream was empty. */
    public boolean isEmpty() { return empty; }
}
