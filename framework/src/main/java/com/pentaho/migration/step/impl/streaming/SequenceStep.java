package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Appends an auto-increment sequence number to each row.
 * Equivalent to Pentaho's Sequence step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code start}     — starting value (default 1)
 *   <li>{@code increment} — increment per row (default 1)
 * </ul>
 */
public class SequenceStep extends AbstractStreamingStep {

    private final AtomicLong counter = new AtomicLong();
    private long increment = 1;

    @Override
    public void configure(Map<String, String> params) {
        long start = Long.parseLong(params.getOrDefault("start", "1"));
        increment  = Long.parseLong(params.getOrDefault("increment", "1"));
        counter.set(start);
    }

    @Override
    protected Row transform(Row row) {
        long seq   = counter.getAndAdd(increment);
        String[] old = row.getValues();
        String[] nw  = new String[old.length + 1];
        System.arraycopy(old, 0, nw, 0, old.length);
        nw[old.length] = String.valueOf(seq);
        return new Row(nw);
    }
}
