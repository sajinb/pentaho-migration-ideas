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
 * <h3>Execution model — DAG-based</h3>
 * <ol>
 *   <li>Build hop graph: outgoing edges and incoming edges per entry.</li>
 *   <li>Topological sort (Kahn's algorithm) to determine a valid processing order.</li>
 *   <li>For each entry (in topo order), create a {@link CompletableFuture}:
 *     <ul>
 *       <li><b>No incoming hops</b> (Start) — runs immediately on a virtual thread.</li>
 *       <li><b>One or more incoming hops</b> — waits for ALL predecessor futures via
 *           {@code CompletableFuture.allOf()}, then checks whether every incoming
 *           hop's evaluation is satisfied; if yes, executes the entry; if no, skips it.</li>
 *     </ul>
 *   </li>
 *   <li>Wait for all futures to complete, then return the AND of all sink-entry results.</li>
 * </ol>
 *
 * <h3>Fan-out (Start → A, B, D in parallel)</h3>
 * A, B, D each have a single predecessor (Start). Once Start's future resolves, all
 * three {@code thenApplyAsync} callbacks are enqueued concurrently on virtual threads.
 *
 * <h3>Fan-in (C waits for both A and B)</h3>
 * C's future is built as {@code CompletableFuture.allOf(futureA, futureB).thenApplyAsync(...)}.
 * It runs exactly once, after the later of A and B completes.
 */
public final class JobExecutor {

    private final JobEntryRegistry entryRegistry;

    public JobExecutor(JobEntryRegistry entryRegistry) {
        this.entryRegistry = entryRegistry;
    }

    public boolean execute(JobDefinition def) throws Exception {
        return execute(def, new HashMap<>());
    }

    public boolean execute(JobDefinition def, Map<String, String> initialContext) throws Exception {
        Map<String, String> context = Collections.synchronizedMap(new HashMap<>(initialContext));

        // ── 1. Build hop topology ──────────────────────────────────────────────
        Map<String, List<EntryHopDefinition>> incoming = new LinkedHashMap<>();
        Map<String, List<String>>             outgoing = new LinkedHashMap<>();
        for (EntryDefinition e : def.entries) {
            incoming.put(e.id, new ArrayList<>());
            outgoing.put(e.id, new ArrayList<>());
        }
        for (EntryHopDefinition hop : def.hops) {
            if (incoming.containsKey(hop.to))   incoming.get(hop.to).add(hop);
            if (outgoing.containsKey(hop.from))  outgoing.get(hop.from).add(hop.to);
        }

        // ── 2. Topological sort ────────────────────────────────────────────────
        List<String> order = topologicalSort(def.entries, incoming, outgoing);

        // ── 3. Build CompletableFuture per entry ───────────────────────────────
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Map<String, CompletableFuture<Boolean>> futures = new LinkedHashMap<>();

            for (String entryId : order) {
                List<EntryHopDefinition> inHops = incoming.get(entryId);

                if (inHops.isEmpty()) {
                    // Source entry (Start) — run immediately
                    final String eid = entryId;
                    futures.put(eid, CompletableFuture.supplyAsync(
                            () -> runEntry(def, eid, context), pool));
                } else {
                    // Capture predecessor futures and hops before the lambda
                    final String eid = entryId;
                    final List<EntryHopDefinition> capturedHops = List.copyOf(inHops);
                    @SuppressWarnings("unchecked")
                    final CompletableFuture<Boolean>[] predFutures = inHops.stream()
                            .map(h -> futures.get(h.from))
                            .toArray(CompletableFuture[]::new);

                    futures.put(eid, CompletableFuture.allOf(predFutures)
                            .thenApplyAsync(ignored -> {
                                // All predecessors done. Check that EVERY incoming hop is satisfied.
                                for (int i = 0; i < capturedHops.size(); i++) {
                                    boolean predSuccess = predFutures[i].join();
                                    if (!shouldFollow(capturedHops.get(i), predSuccess)) {
                                        return true; // skip this entry
                                    }
                                }
                                return runEntry(def, eid, context);
                            }, pool));
                }
            }

            // ── 4. Wait for all futures; return AND of sink results ────────────
            CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).get();

            Set<String> hasSucessors = outgoing.values().stream()
                    .flatMap(List::stream).collect(Collectors.toSet());
            boolean allSuccess = true;
            for (Map.Entry<String, CompletableFuture<Boolean>> e : futures.entrySet()) {
                if (!hasSucessors.contains(e.getKey()) && Boolean.FALSE.equals(e.getValue().get())) {
                    allSuccess = false;
                }
            }
            return allSuccess;

        } finally {
            pool.shutdown();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private boolean runEntry(JobDefinition def, String entryId, Map<String, String> context) {
        try {
            EntryDefinition entryDef = findEntry(def, entryId);
            JobEntry entry = entryRegistry.instantiate(
                    entryDef.type, entryDef.params != null ? entryDef.params : Map.of());
            return entry.execute(context);
        } catch (Exception ex) {
            throw new RuntimeException("Entry '" + entryId + "' failed: " + ex.getMessage(), ex);
        }
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

    /**
     * Kahn's algorithm. Entries with no incoming hops come first; the rest follow
     * in dependency order. Any entries not reachable via hops are appended at the end.
     */
    private static List<String> topologicalSort(
            List<EntryDefinition> entries,
            Map<String, List<EntryHopDefinition>> incoming,
            Map<String, List<String>> outgoing) {

        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (EntryDefinition e : entries) inDegree.put(e.id, incoming.get(e.id).size());

        Queue<String> queue = new ArrayDeque<>();
        for (EntryDefinition e : entries) {
            if (inDegree.get(e.id) == 0) queue.add(e.id);
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            order.add(id);
            for (String successor : outgoing.getOrDefault(id, List.of())) {
                int remaining = inDegree.merge(successor, -1, Integer::sum);
                if (remaining == 0) queue.add(successor);
            }
        }

        // Append any remaining (cyclic or disconnected) entries
        for (EntryDefinition e : entries) {
            if (!order.contains(e.id)) order.add(e.id);
        }
        return order;
    }

    private EntryDefinition findEntry(JobDefinition def, String id) {
        return def.entries.stream()
                .filter(e -> id.equals(e.id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Entry not found: " + id));
    }
}
