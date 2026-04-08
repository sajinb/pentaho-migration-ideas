package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * A single KTR step in a transformation pipeline.
 *
 * <p>Source steps (no incoming hops) receive an empty list for {@code inputs}.
 * Single-input steps use {@code inputs.get(0)}.
 * Multi-input steps (join, append) use all entries in order of hop appearance in YAML.
 *
 * <p>Implementations may be:
 * <ul>
 *   <li>Streaming: each row transformed independently (filter, project, string ops)
 *   <li>Blocking: must consume all input before emitting any output (sort, group-by)
 *   <li>Source: produce rows from an external source (CSV, DB, Excel)
 *   <li>Sink: consume all rows, write to destination, return empty iterator
 * </ul>
 */
public interface Step {

    /**
     * Configure this step from the params map in the YAML definition.
     * Called once before {@link #apply(List)}.
     */
    default void configure(Map<String, String> params) {}

    /**
     * Apply this step: consume upstream rows and produce output rows.
     *
     * @param inputs upstream iterators. Empty for source steps.
     * @return iterator of output rows; empty iterator for sink steps.
     */
    Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception;
}
