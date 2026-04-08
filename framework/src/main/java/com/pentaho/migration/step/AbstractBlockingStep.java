package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Base class for steps that must consume ALL input rows before emitting any output.
 * Examples: SortRows, GroupBy, MemoryGroupBy, Unique, Denormaliser, BlockingStep.
 *
 * <p>Note: for very large datasets, prefer steps that stream output (like SortRows
 * which wraps ExternalMergeSort) over in-memory accumulation.
 */
public abstract class AbstractBlockingStep implements Step {

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        List<Row> rows = new ArrayList<>();
        Iterator<Row> upstream = inputs.get(0);
        while (upstream.hasNext()) rows.add(upstream.next());
        return process(rows);
    }

    /**
     * Process the full accumulated list of input rows and return output rows.
     */
    protected abstract Iterator<Row> process(List<Row> rows) throws Exception;
}
