package com.pentaho.migration.engine;

import com.pentaho.migration.entry.JobEntry;
import com.pentaho.migration.entry.JobEntryRegistry;
import com.pentaho.migration.model.EntryDefinition;
import com.pentaho.migration.model.EntryHopDefinition;
import com.pentaho.migration.model.JobDefinition;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Executes a KJB job defined by a {@link JobDefinition}.
 *
 * <h3>Execution model</h3>
 * <ol>
 *   <li>Locate the START entry (type == "Start")
 *   <li>Follow hops based on evaluation: "success", "failure", or "unconditional"
 *   <li>When multiple outgoing hops have the same evaluation, run branches in parallel
 *       using Java 21 {@link StructuredTaskScope.ShutdownOnFailure}
 * </ol>
 */
public final class JobExecutor {

    private final JobEntryRegistry entryRegistry;

    public JobExecutor(JobEntryRegistry entryRegistry) {
        this.entryRegistry = entryRegistry;
    }

    /**
     * Execute the job with an empty context.
     *
     * @return true if the job completed successfully; false on failure
     */
    public boolean execute(JobDefinition def) throws Exception {
        return execute(def, new HashMap<>());
    }

    /**
     * Execute the job with the provided initial context.
     * Callers may populate {@code basePath} (directory containing YAML files),
     * or any other key-value pairs that job entries read from context.
     *
     * @return true if the job completed successfully; false on failure
     */
    public boolean execute(JobDefinition def, Map<String, String> initialContext) throws Exception {
        Map<String, String> context = new HashMap<>(initialContext);

        // Find the START entry
        EntryDefinition start = def.entries.stream()
                .filter(e -> "Start".equals(e.type))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Job '" + def.name + "' has no Start entry"));

        return runEntry(def, start.id, context);
    }

    // -------------------------------------------------------------------------
    // Recursive entry execution
    // -------------------------------------------------------------------------

    private boolean runEntry(JobDefinition def, String entryId,
                              Map<String, String> context) throws Exception {
        EntryDefinition entryDef = findEntry(def, entryId);
        JobEntry entry = entryRegistry.instantiate(entryDef.type,
                entryDef.params != null ? entryDef.params : Map.of());

        boolean success = entry.execute(context);

        // Find next hops
        List<EntryHopDefinition> nextHops = def.hops.stream()
                .filter(h -> entryId.equals(h.from))
                .filter(h -> shouldFollow(h, success))
                .toList();

        if (nextHops.isEmpty()) return success;

        if (nextHops.size() == 1) {
            return runEntry(def, nextHops.get(0).to, context);
        }

        // Multiple hops: run in parallel with StructuredTaskScope
        return runParallelBranches(def, nextHops, context);
    }

    private boolean shouldFollow(EntryHopDefinition hop, boolean entrySuccess) {
        if (hop.evaluation == null) return true;
        return switch (hop.evaluation.toLowerCase()) {
            case "unconditional" -> true;
            case "success"       -> entrySuccess;
            case "failure"       -> !entrySuccess;
            default              -> true;
        };
    }

    // -------------------------------------------------------------------------
    // Parallel branch execution (StructuredTaskScope — Java 21)
    // -------------------------------------------------------------------------

    private boolean runParallelBranches(JobDefinition def,
                                         List<EntryHopDefinition> hops,
                                         Map<String, String> context) throws Exception {
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Boolean>> futures = hops.stream()
                    .map(hop -> pool.submit(() -> runEntry(def, hop.to, new HashMap<>(context))))
                    .collect(Collectors.toList());
            boolean allSuccess = true;
            for (Future<Boolean> f : futures) {
                if (!f.get()) allSuccess = false;
            }
            return allSuccess;
        } finally {
            pool.shutdown();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private EntryDefinition findEntry(JobDefinition def, String id) {
        return def.entries.stream()
                .filter(e -> id.equals(e.id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Entry not found: " + id));
    }
}
