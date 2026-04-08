package com.pentaho.migration.engine;

import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.RoutingStep;
import com.pentaho.migration.step.Step;
import com.pentaho.migration.step.StepRegistry;
import com.pentaho.migration.step.impl.sink.MappingOutputStep;
import com.pentaho.migration.step.impl.source.MappingInputStep;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Executes a KTR transformation defined by a {@link TransformationDefinition}.
 *
 * <h3>DAG execution model</h3>
 * <ol>
 *   <li>Build adjacency maps (incoming/outgoing hops per step ID)
 *   <li>Topological sort (Kahn's algorithm)
 *   <li>Execute steps in topological order, chaining iterators:
 *     <ul>
 *       <li><b>Linear</b>: pass iterator directly (no materialization)
 *       <li><b>Fan-in</b>: collect all upstream iterators into a List, pass to step
 *       <li><b>Fan-out (broadcast)</b>: materialize to {@link BroadcastBuffer}, one reader per branch
 *       <li><b>Fan-out (routing)</b>: call {@link RoutingStep#route} to get per-branch iterators
 *       <li><b>Sink</b>: drain the output iterator eagerly
 *     </ul>
 * </ol>
 */
public final class TransformationExecutor {

    private final StepRegistry registry;
    private Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"));

    public TransformationExecutor(StepRegistry registry) {
        this.registry = registry;
    }

    public TransformationExecutor withTempDir(Path tempDir) {
        this.tempDir = tempDir;
        return this;
    }

    // -------------------------------------------------------------------------
    // Public execute entry point
    // -------------------------------------------------------------------------

    /**
     * Execute the transformation from end to end.
     * Sink steps (no outgoing hops) are drained eagerly.
     */
    public void execute(TransformationDefinition def) throws Exception {
        Map<String, Iterator<Row>> stepOutputs = buildAndExecute(def, null, null);
        // Drain any remaining non-sink iterators (shouldn't happen in a well-formed pipeline)
        for (Iterator<Row> it : stepOutputs.values()) {
            while (it.hasNext()) it.next();
        }
    }

    /**
     * Execute a sub-transformation (Mapping): feeds rows to the MappingInput step
     * and returns rows emitted by the MappingOutput step.
     */
    public Iterator<Row> executeMapping(TransformationDefinition def,
                                         Iterator<Row> inputRows) throws Exception {
        MappingInputStep  mappingIn  = new MappingInputStep();
        MappingOutputStep mappingOut = new MappingOutputStep();
        mappingIn.feed(inputRows);

        // Register overrides for the MappingInput/MappingOutput instances
        Map<String, Step> overrides = new HashMap<>();
        for (StepDefinition sd : def.steps) {
            if ("MappingInput".equals(sd.type))  overrides.put(sd.id, mappingIn);
            if ("MappingOutput".equals(sd.type)) overrides.put(sd.id, mappingOut);
        }

        buildAndExecute(def, overrides, null);
        return mappingOut.getCollected();
    }

    // -------------------------------------------------------------------------
    // Core DAG execution
    // -------------------------------------------------------------------------

    /**
     * Build the hop graph, topologically sort, and execute all steps.
     *
     * @param overrides pre-built step instances (used for Mapping input/output)
     * @param extraOutputs if non-null, collect final iterator per step ID
     * @return map of stepId → output iterator for steps that still have output (rare)
     */
    private Map<String, Iterator<Row>> buildAndExecute(
            TransformationDefinition def,
            Map<String, Step> overrides,
            Map<String, Iterator<Row>> extraOutputs) throws Exception {

        // --- 1. Build adjacency maps ---
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();

        for (StepDefinition sd : def.steps) {
            outgoing.put(sd.id, new ArrayList<>());
            incoming.put(sd.id, new ArrayList<>());
        }
        for (HopDefinition hop : def.hops) {
            if (!hop.enabled) continue;
            outgoing.computeIfAbsent(hop.from, k -> new ArrayList<>()).add(hop.to);
            incoming.computeIfAbsent(hop.to,   k -> new ArrayList<>()).add(hop.from);
        }

        // --- 2. Topological sort (Kahn's algorithm) ---
        List<String> order = topologicalSort(def.steps, incoming, outgoing);

        // --- 3. Execute in order ---
        // stepOutputs maps "stepId" or "stepId#targetId" → iterator
        Map<String, Iterator<Row>> stepOutputs = new HashMap<>();

        for (String stepId : order) {
            StepDefinition stepDef = findStep(def, stepId);
            Step step = (overrides != null && overrides.containsKey(stepId))
                    ? overrides.get(stepId)
                    : registry.instantiate(stepDef.type,
                            stepDef.params != null ? stepDef.params : Map.of());

            // Collect upstream iterators (fan-in: ordered by incoming list)
            List<Iterator<Row>> inputs = incoming.getOrDefault(stepId, List.of())
                    .stream()
                    .map(upstreamId -> {
                        // First try direct lookup, then look for routing-tagged key
                        Iterator<Row> it = stepOutputs.get(upstreamId);
                        if (it == null) {
                            // Look for "upstreamId#stepId" (routing step output)
                            it = stepOutputs.get(upstreamId + "#" + stepId);
                        }
                        return it != null ? it : Collections.<Row>emptyIterator();
                    })
                    .collect(Collectors.toList());

            List<String> downstreamIds = outgoing.getOrDefault(stepId, List.of());

            if (step instanceof RoutingStep rs) {
                // --- Conditional routing fan-out ---
                Map<String, Iterator<Row>> routes = rs.route(inputs);
                routes.forEach((targetId, iter) ->
                        stepOutputs.put(stepId + "#" + targetId, iter));

            } else {
                Iterator<Row> output = step.apply(inputs);

                if (downstreamIds.isEmpty()) {
                    // Sink: drain eagerly
                    while (output.hasNext()) output.next();

                } else if (downstreamIds.size() == 1) {
                    // Linear pass-through (no materialization)
                    stepOutputs.put(stepId, output);

                } else {
                    // Broadcast fan-out via BroadcastBuffer
                    BroadcastBuffer buf = BroadcastBuffer.materialize(
                            output, downstreamIds.size(), tempDir);
                    for (int i = 0; i < downstreamIds.size(); i++) {
                        // Store under "stepId#downstreamId" so the downstream step can find it
                        stepOutputs.put(stepId + "#" + downstreamIds.get(i),
                                buf.readerForBranch(i));
                    }
                }
            }
        }

        return stepOutputs;
    }

    // -------------------------------------------------------------------------
    // Topological sort — Kahn's algorithm
    // -------------------------------------------------------------------------

    private List<String> topologicalSort(List<StepDefinition> steps,
                                          Map<String, List<String>> incoming,
                                          Map<String, List<String>> outgoing) {
        // Kahn's algorithm: process zero-in-degree steps first
        Map<String, Integer> inDegree = new HashMap<>();
        for (StepDefinition sd : steps) {
            inDegree.put(sd.id, incoming.getOrDefault(sd.id, List.of()).size());
        }

        // Use a stable queue that processes in definition order for determinism
        Queue<String> queue = new ArrayDeque<>();
        for (StepDefinition sd : steps) {
            if (inDegree.get(sd.id) == 0) queue.add(sd.id);
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String stepId = queue.poll();
            order.add(stepId);
            for (String downstream : outgoing.getOrDefault(stepId, List.of())) {
                int deg = inDegree.merge(downstream, -1, Integer::sum);
                if (deg == 0) queue.add(downstream);
            }
        }

        // Fallback: add any remaining steps (handles cycles or disconnected nodes)
        Set<String> placed = new HashSet<>(order);
        for (StepDefinition sd : steps) {
            if (!placed.contains(sd.id)) order.add(sd.id);
        }

        return order;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StepDefinition findStep(TransformationDefinition def, String id) {
        return def.steps.stream()
                .filter(s -> id.equals(s.id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Step not found: " + id));
    }
}
