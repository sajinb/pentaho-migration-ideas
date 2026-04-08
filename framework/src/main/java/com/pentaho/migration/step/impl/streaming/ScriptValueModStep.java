package com.pentaho.migration.step.impl.streaming;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractStreamingStep;

import java.util.Map;

/**
 * Applies a JavaScript/Groovy script to each row.
 * Equivalent to Pentaho's ScriptValueMod step.
 *
 * <p><b>Phase 3 stub</b> — requires GraalVM JS or Groovy runtime (not yet added to pom.xml).
 * Throws {@link UnsupportedOperationException} until implemented.
 */
public class ScriptValueModStep extends AbstractStreamingStep {

    @Override
    public void configure(Map<String, String> params) {
        // Phase 3: parse and compile the script
    }

    @Override
    protected Row transform(Row row) {
        throw new UnsupportedOperationException(
                "ScriptValueModStep requires Phase 3 (GraalVM JS / Groovy) — not yet implemented.");
    }
}
