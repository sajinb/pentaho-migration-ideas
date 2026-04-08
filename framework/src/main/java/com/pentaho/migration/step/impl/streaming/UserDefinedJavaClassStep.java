package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Executes inline Java code compiled at runtime via {@code javax.tools}.
 * Equivalent to Pentaho's UserDefinedJavaClass step.
 *
 * <p><b>Phase 3 stub</b> — requires javax.tools (JDK, not JRE) and dynamic classloading.
 * Throws {@link UnsupportedOperationException} until implemented.
 */
public class UserDefinedJavaClassStep extends AbstractStreamingStep {

    @Override
    public void configure(Map<String, String> params) {
        // Phase 3: compile and load the user-defined class
    }

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "UserDefinedJavaClassStep requires Phase 3 (javax.tools runtime compile) — not yet implemented.");
    }
}
