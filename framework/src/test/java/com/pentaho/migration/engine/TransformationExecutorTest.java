package com.pentaho.migration.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.entry.JobEntryRegistry;
import com.pentaho.migration.model.*;
import com.pentaho.migration.step.StepRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the declarative pipeline execution engine.
 */
class TransformationExecutorTest {

    @TempDir Path tmp;

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    // -------------------------------------------------------------------------
    // Helper: write a CSV file to the temp dir
    // -------------------------------------------------------------------------

    private Path writeCsv(String name, String... lines) throws Exception {
        Path p = tmp.resolve(name);
        Files.write(p, Arrays.asList(lines));
        return p;
    }

    private List<String[]> readCsv(Path p) throws Exception {
        List<String> lines = Files.readAllLines(p);
        List<String[]> rows = new ArrayList<>();
        for (String line : lines) rows.add(line.split(",", -1));
        return rows;
    }

    // -------------------------------------------------------------------------
    // 1. Linear pipeline: CsvInput → DummyStep → TextFileOutput
    // -------------------------------------------------------------------------

    @Test
    void linearPipeline() throws Exception {
        Path input  = writeCsv("in.csv", "name,age", "Bob,30", "Alice,25", "Carol,35");
        Path output = tmp.resolve("out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "linear";
        def.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString())),
            step("out",  "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(3, rows.size());
        assertEquals("Bob", rows.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // 2. Sort pipeline: CsvInput → SortRows → TextFileOutput
    // -------------------------------------------------------------------------

    @Test
    void sortPipeline() throws Exception {
        Path input  = writeCsv("sort_in.csv", "name,age",
                "Bob,30", "Alice,25", "Carol,35", "Dave,20");
        Path output = tmp.resolve("sort_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "sort";
        def.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString())),
            step("sort", "SortRows",       Map.of("columns", "0")),
            step("out",  "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "sort"), hop("sort", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(4, rows.size());
        assertEquals("Alice", rows.get(0)[0]);
        assertEquals("Bob",   rows.get(1)[0]);
        assertEquals("Carol", rows.get(2)[0]);
        assertEquals("Dave",  rows.get(3)[0]);
    }

    // -------------------------------------------------------------------------
    // 3. Fan-in pipeline: CsvInput_A + CsvInput_B → AppendStep → TextFileOutput
    // -------------------------------------------------------------------------

    @Test
    void fanInPipeline() throws Exception {
        Path inputA = writeCsv("a.csv", "name", "Alice", "Bob");
        Path inputB = writeCsv("b.csv", "name", "Carol", "Dave");
        Path output = tmp.resolve("fanin_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "fanin";
        def.steps = List.of(
            step("srcA",   "CsvInput",      Map.of("filePath", inputA.toString())),
            step("srcB",   "CsvInput",      Map.of("filePath", inputB.toString())),
            step("append", "Append",         Map.of()),
            step("out",    "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(
            hop("srcA", "append"),
            hop("srcB", "append"),
            hop("append", "out")
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(4, rows.size());
    }

    // -------------------------------------------------------------------------
    // 4. Fan-out (broadcast): CsvInput → SortRows ──► Output_A
    //                                              └──► Output_B
    // -------------------------------------------------------------------------

    @Test
    void fanOutBroadcastPipeline() throws Exception {
        Path input   = writeCsv("fo_in.csv", "name", "Bob", "Alice", "Carol");
        Path outputA = tmp.resolve("fo_out_a.csv");
        Path outputB = tmp.resolve("fo_out_b.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "fanout";
        def.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString())),
            step("sort", "SortRows",      Map.of("columns", "0")),
            step("outA", "TextFileOutput", Map.of("filePath", outputA.toString())),
            step("outB", "TextFileOutput", Map.of("filePath", outputB.toString()))
        );
        def.hops = List.of(
            hop("src",  "sort"),
            hop("sort", "outA"),
            hop("sort", "outB")
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rowsA = readCsv(outputA);
        List<String[]> rowsB = readCsv(outputB);
        assertEquals(3, rowsA.size());
        assertEquals(3, rowsB.size());
        // Both outputs should be sorted
        assertEquals("Alice", rowsA.get(0)[0]);
        assertEquals("Alice", rowsB.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // 5. Routing (FilterRows): CsvInput → FilterRows ──► out_match (trueStep)
    //                                                └──► out_rest  (falseStep)
    // -------------------------------------------------------------------------

    @Test
    void filterRowsRoutingPipeline() throws Exception {
        Path input    = writeCsv("fr_in.csv", "status", "ACTIVE", "INACTIVE", "ACTIVE", "PENDING");
        Path outMatch = tmp.resolve("match.csv");
        Path outRest  = tmp.resolve("rest.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "filter_route";
        def.steps = List.of(
            step("src",      "CsvInput",      Map.of("filePath", input.toString())),
            step("filter",   "FilterRows",    Map.of("column", "0", "value", "ACTIVE",
                                                     "trueStep", "outMatch", "falseStep", "outRest")),
            step("outMatch", "TextFileOutput", Map.of("filePath", outMatch.toString())),
            step("outRest",  "TextFileOutput", Map.of("filePath", outRest.toString()))
        );
        def.hops = List.of(
            hop("src",    "filter"),
            hop("filter", "outMatch"),
            hop("filter", "outRest")
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> matched = readCsv(outMatch);
        List<String[]> rested  = readCsv(outRest);
        assertEquals(2, matched.size());  // two ACTIVE rows
        assertEquals(2, rested.size());   // INACTIVE + PENDING
        assertTrue(Arrays.stream(matched.toArray()).map(r -> ((String[])r)[0]).allMatch("ACTIVE"::equals));
    }

    // -------------------------------------------------------------------------
    // 6. Streaming steps: SelectValues + IfNull + StringOperations
    // -------------------------------------------------------------------------

    @Test
    void streamingStepsPipeline() throws Exception {
        Path input  = writeCsv("ss_in.csv", "first,last", "alice,smith", ",jones");
        Path output = tmp.resolve("ss_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "streaming";
        def.steps = List.of(
            step("src",    "CsvInput",         Map.of("filePath", input.toString())),
            step("ifnull", "IfNull",           Map.of("column", "0", "defaultValue", "unknown")),
            step("upper",  "StringOperations", Map.of("column", "0", "operation", "upper")),
            step("sel",    "SelectValues",     Map.of("columns", "0")),
            step("out",    "TextFileOutput",   Map.of("filePath", output.toString()))
        );
        def.hops = List.of(
            hop("src", "ifnull"), hop("ifnull", "upper"), hop("upper", "sel"), hop("sel", "out")
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(2, rows.size());
        assertEquals("ALICE",   rows.get(0)[0]);
        assertEquals("UNKNOWN", rows.get(1)[0]);
    }

    // -------------------------------------------------------------------------
    // 7. Job executor: Start → RunTransformation → Success
    // -------------------------------------------------------------------------

    @Test
    void jobExecutorRunsTransformation() throws Exception {
        // Write a simple sort transformation YAML
        Path input  = writeCsv("job_in.csv", "name", "Charlie", "Alice", "Bob");
        Path output = tmp.resolve("job_out.csv");

        TransformationDefinition tDef = new TransformationDefinition();
        tDef.name  = "job_inner";
        tDef.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString())),
            step("sort", "SortRows",      Map.of("columns", "0")),
            step("out",  "TextFileOutput", Map.of("filePath", output.toString()))
        );
        tDef.hops = List.of(hop("src", "sort"), hop("sort", "out"));

        Path tYaml = tmp.resolve("sort_inner.yaml");
        YAML.writeValue(tYaml.toFile(), tDef);

        // Build job definition
        JobDefinition jDef = new JobDefinition();
        jDef.name    = "test_job";
        jDef.entries = List.of(
            entry("start",  "Start",            Map.of()),
            entry("run",    "RunTransformation", Map.of("transformationPath", tYaml.toString())),
            entry("finish", "Success",           Map.of())
        );
        jDef.hops = List.of(
            entryHop("start",  "run",    "unconditional"),
            entryHop("run",    "finish", "success")
        );

        boolean result = new JobExecutor(JobEntryRegistry.withDefaults()).execute(jDef);

        assertTrue(result);
        List<String[]> rows = readCsv(output);
        assertEquals(3, rows.size());
        assertEquals("Alice",   rows.get(0)[0]);
        assertEquals("Bob",     rows.get(1)[0]);
        assertEquals("Charlie", rows.get(2)[0]);
    }

    // -------------------------------------------------------------------------
    // 9. Non-linear job: parallel fan-out + fan-in + independent branch
    // -------------------------------------------------------------------------

    @Test
    void nonLinearJob_parallelFanOutAndFanIn() throws Exception {
        // Topology:
        //   Start ──► Run TransformA ──┐
        //         ├──► Run TransformB ─┴──► Run TransformC   (fan-in: waits for both)
        //         └──► Run TransformD  (independent)
        //
        // Expected: A and B run concurrently; C runs ONCE after BOTH complete;
        //           D runs independently in parallel with A/B/C.

        // Thread-safe list records the exact execution order
        List<String> executionOrder = Collections.synchronizedList(new ArrayList<>());

        // Registry using factories so we can intercept execution and record order.
        // RunTransformation entries just record their name and return success —
        // no actual YAML file needed.
        JobEntryRegistry registry = JobEntryRegistry.withDefaults();
        for (String entryName : List.of("Run TransformA", "Run TransformB",
                                         "Run TransformC", "Run TransformD")) {
            final String name = entryName;
            registry.registerFactory("Track_" + entryName, params -> ctx -> {
                executionOrder.add(name);
                return true;
            });
        }

        JobDefinition jDef = new JobDefinition();
        jDef.name    = "NonLinear_Orchestration_Job";
        jDef.entries = List.of(
            entry("Start",           "Start",               Map.of()),
            entry("Run TransformA",  "Track_Run TransformA", Map.of()),
            entry("Run TransformB",  "Track_Run TransformB", Map.of()),
            entry("Run TransformC",  "Track_Run TransformC", Map.of()),
            entry("Run TransformD",  "Track_Run TransformD", Map.of())
        );
        jDef.hops = List.of(
            entryHop("Start",          "Run TransformA", "success"),
            entryHop("Start",          "Run TransformB", "success"),
            entryHop("Start",          "Run TransformD", "success"),
            entryHop("Run TransformA", "Run TransformC", "success"),
            entryHop("Run TransformB", "Run TransformC", "success")
        );

        boolean result = new JobExecutor(registry).execute(jDef);

        assertTrue(result, "Job should complete successfully");

        // C must appear exactly once (fan-in: not duplicated by each predecessor branch)
        assertEquals(1, Collections.frequency(executionOrder, "Run TransformC"),
                "TransformC must run exactly once, not once per predecessor branch");

        // A, B, D each run exactly once
        assertEquals(1, Collections.frequency(executionOrder, "Run TransformA"));
        assertEquals(1, Collections.frequency(executionOrder, "Run TransformB"));
        assertEquals(1, Collections.frequency(executionOrder, "Run TransformD"));

        // C must come after both A and B (fan-in ordering guarantee)
        int idxA = executionOrder.indexOf("Run TransformA");
        int idxB = executionOrder.indexOf("Run TransformB");
        int idxC = executionOrder.indexOf("Run TransformC");
        assertTrue(idxC > idxA, "TransformC must execute after TransformA completes");
        assertTrue(idxC > idxB, "TransformC must execute after TransformB completes");

        // Total: A + B + C + D = 4 executions (Start entry records nothing)
        assertEquals(4, executionOrder.size());
    }

    // -------------------------------------------------------------------------
    // Skip propagation: mutually-exclusive branches converging at a terminal
    // -------------------------------------------------------------------------

    @Test
    void mutuallyExclusiveBranches_terminalRunsOnce() throws Exception {
        // Topology (mirrors the SUCCESS terminal pattern in real KJBs):
        //
        //   Start ──success──► Gate ──success──► BranchA ──success──► Terminal
        //                         └──failure──► BranchB ──success──► Terminal
        //
        // Gate succeeds → BranchA runs; BranchB is SKIPPED (failure hop from a successful gate).
        // Terminal has two incoming success hops: from BranchA and from BranchB.
        // Expected: Terminal runs exactly ONCE via BranchA; the skipped BranchB must NOT
        // incorrectly satisfy Terminal's other incoming hop and cause it to be skipped entirely.
        //
        // Before the skip-propagation fix, BranchB (skipped) returned `true`, which propagated
        // as "success" to Terminal, causing Terminal to appear to have been reached from both
        // branches and run with garbled semantics.

        List<String> ran = Collections.synchronizedList(new ArrayList<>());

        JobEntryRegistry registry = new JobEntryRegistry();
        registry.register("Start",   com.pentaho.migration.entry.impl.StartEntry.class);
        registry.registerFactory("GateEntry",    p -> ctx -> { ran.add("Gate");     return true;  });
        registry.registerFactory("BranchAEntry", p -> ctx -> { ran.add("BranchA"); return true;  });
        registry.registerFactory("BranchBEntry", p -> ctx -> { ran.add("BranchB"); return true;  });
        registry.registerFactory("TerminalEntry",p -> ctx -> { ran.add("Terminal"); return true; });

        JobDefinition jDef = new JobDefinition();
        jDef.name    = "exclusive_branches";
        jDef.entries = List.of(
            entry("Start",    "Start",        Map.of()),
            entry("Gate",     "GateEntry",    Map.of()),
            entry("BranchA",  "BranchAEntry", Map.of()),
            entry("BranchB",  "BranchBEntry", Map.of()),
            entry("Terminal", "TerminalEntry", Map.of())
        );
        jDef.hops = List.of(
            entryHop("Start",   "Gate",     "unconditional"),
            entryHop("Gate",    "BranchA",  "success"),   // taken  (Gate succeeds)
            entryHop("Gate",    "BranchB",  "failure"),   // skipped (Gate succeeds)
            entryHop("BranchA", "Terminal", "success"),
            entryHop("BranchB", "Terminal", "success")
        );

        boolean result = new JobExecutor(registry).execute(jDef);
        assertTrue(result);

        // Gate and BranchA ran; BranchB was skipped
        assertTrue(ran.contains("Gate"),    "Gate must run");
        assertTrue(ran.contains("BranchA"), "BranchA must run (success branch)");
        assertFalse(ran.contains("BranchB"),"BranchB must NOT run (failure branch skipped)");

        // Terminal must run exactly once via BranchA
        assertEquals(1, Collections.frequency(ran, "Terminal"),
                "Terminal must run exactly once — not skipped due to BranchB's skipped state");
    }

    @Test
    void memoryGroupByStep() throws Exception {
        Path input  = writeCsv("gb_in.csv", "dept,salary",
                "Engineering,100", "Engineering,120", "HR,80", "HR,90");
        Path output = tmp.resolve("gb_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "groupby";
        def.steps = List.of(
            step("src", "CsvInput",       Map.of("filePath", input.toString())),
            step("gb",  "MemoryGroupBy",  Map.of("groupColumns", "0",
                                                  "aggColumns", "1",
                                                  "aggFunctions", "SUM")),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "gb"), hop("gb", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(2, rows.size());
        // Engineering: 100+120=220, HR: 80+90=170
        Map<String, String> sums = new HashMap<>();
        for (String[] row : rows) sums.put(row[0], row[1]);
        assertEquals("220.0", sums.get("Engineering"));
        assertEquals("170.0", sums.get("HR"));
    }

    // -------------------------------------------------------------------------
    // Variable substitution — ${VAR} tokens resolved from def.parameters
    // -------------------------------------------------------------------------

    @Test
    void variableSubstitution_resolvedFromParameters() throws Exception {
        Path input  = writeCsv("var_in.csv", "name,score", "Alice,90", "Bob,70");
        Path output = tmp.resolve("var_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name       = "var_test";
        def.parameters = Map.of(
                "INPUT_PATH",  input.toString(),
                "OUTPUT_PATH", output.toString());
        def.steps = List.of(
            step("src", "CsvInput",       Map.of("filePath", "${INPUT_PATH}")),
            step("out", "TextFileOutput", Map.of("filePath", "${OUTPUT_PATH}"))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // StreamLookup — hash join: main stream enriched with lookup values
    // -------------------------------------------------------------------------

    @Test
    void streamLookup_enrichesMainStreamWithLookupValues() throws Exception {
        // Main stream: order_id, customer
        Path orders = writeCsv("orders.csv", "order_id,customer",
                "101,Alice", "102,Bob", "103,Carol");
        // Lookup stream: order_id, price
        Path prices = writeCsv("prices.csv", "order_id,price",
                "101,9.99", "102,19.99", "999,0.01");   // 999 has no match in main
        Path output = tmp.resolve("enriched.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "lookup_test";
        def.steps = List.of(
            step("orders", "CsvInput", Map.of("filePath", orders.toString())),
            step("prices", "CsvInput", Map.of("filePath", prices.toString())),
            step("enrich", "StreamLookup", Map.of(
                    "lookupInputIndex", "1",  // prices is second input
                    "keyStreamCols",    "0",  // order_id in main stream
                    "keyLookupCols",    "0",  // order_id in lookup stream
                    "valueFieldCols",   "1"   // price column (index 1 in lookup)
            )),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        // hops: orders→enrich, prices→enrich (lookup), enrich→out
        def.hops = List.of(
            hop("orders", "enrich"),
            hop("prices", "enrich"),
            hop("enrich", "out")
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        // 3 main rows; 103/Carol has no match → price column is null (empty string in CSV)
        assertEquals(3, rows.size());
        assertEquals("101",  rows.get(0)[0]);
        assertEquals("9.99", rows.get(0)[2]);   // price appended as col 2
        assertEquals("102",  rows.get(1)[0]);
        assertEquals("19.99", rows.get(1)[2]);
        assertEquals("103",  rows.get(2)[0]);
        assertEquals("",     rows.get(2)[2]);   // no match → null → empty in CSV
    }

    // -------------------------------------------------------------------------
    // TextFileOutput header writing + chained-KTR read-back
    // -------------------------------------------------------------------------

    @Test
    void textFileOutputWritesHeader_andChainedKtrReadsItCorrectly() throws Exception {
        // KTR 1: CsvInput (with header) → SortRows → TextFileOutput (writeHeader=true, fieldNames set)
        // KTR 2: CsvInput (with header, reads KTR-1 output) → TextFileOutput
        // Without header writing, KTR 2 would skip the first data row, losing a record.

        Path input   = writeCsv("input.csv",
                "id,first_name,last_name,status",
                "1,Alice,Ng,ACTIVE",
                "3,Chandra,Khan,ACTIVE");
        Path middle  = tmp.resolve("filtered.csv");
        Path output  = tmp.resolve("final.csv");

        // ── KTR 1: sort by last_name, first_name, write with header ───────────
        TransformationDefinition ktr1 = new TransformationDefinition();
        ktr1.name  = "sort_ktr";
        ktr1.steps = List.of(
            step("src",  "CsvInput",       Map.of("filePath", input.toString(), "hasHeader", "true")),
            step("sort", "SortRows",        Map.of("columns", "2,1")),  // last_name=2, first_name=1
            step("out",  "TextFileOutput",  Map.of(
                "filePath",    middle.toString(),
                "writeHeader", "true",
                "fieldNames",  "id,first_name,last_name,status",
                "outputCols",  "0,1,2,3"
            ))
        );
        ktr1.hops = List.of(hop("src", "sort"), hop("sort", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(ktr1);

        // Verify KTR 1 output has header + 2 data rows, sorted Khan < Ng
        List<String> ktr1Lines = Files.readAllLines(middle);
        assertEquals(3, ktr1Lines.size(), "header + 2 data rows");
        assertEquals("id,first_name,last_name,status", ktr1Lines.get(0), "header row");
        assertTrue(ktr1Lines.get(1).contains("Khan"),   "Khan (sorted first alphabetically)");
        assertTrue(ktr1Lines.get(2).contains("Ng"),     "Ng (sorted second)");

        // ── KTR 2: read the output of KTR 1 (which has a header), write final ──
        TransformationDefinition ktr2 = new TransformationDefinition();
        ktr2.name  = "write_ktr";
        ktr2.steps = List.of(
            step("src2", "CsvInput",      Map.of("filePath", middle.toString(), "hasHeader", "true")),
            step("out2", "TextFileOutput", Map.of(
                "filePath",    output.toString(),
                "writeHeader", "true",
                "fieldNames",  "id,first_name,last_name,status",
                "outputCols",  "0,1,2,3"
            ))
        );
        ktr2.hops = List.of(hop("src2", "out2"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(ktr2);

        // Both records must survive the chain: neither Alice/Ng nor Chandra/Khan dropped
        List<String> finalLines = Files.readAllLines(output);
        assertEquals(3, finalLines.size(), "header + 2 data rows in final output");
        assertEquals("id,first_name,last_name,status", finalLines.get(0));
        long khans = finalLines.stream().filter(l -> l.contains("Khan")).count();
        long ngs   = finalLines.stream().filter(l -> l.contains("Ng")).count();
        assertEquals(1, khans, "Chandra/Khan must appear in final output");
        assertEquals(1, ngs,   "Alice/Ng must appear in final output");
    }

    // -------------------------------------------------------------------------
    // Builder helpers
    // -------------------------------------------------------------------------

    private StepDefinition step(String id, String type, Map<String, String> params) {
        StepDefinition sd = new StepDefinition();
        sd.id = id; sd.type = type;
        sd.params = params.isEmpty() ? null : new HashMap<>(params);
        return sd;
    }

    private HopDefinition hop(String from, String to) {
        HopDefinition h = new HopDefinition();
        h.from = from; h.to = to; h.enabled = true;
        return h;
    }

    private EntryDefinition entry(String id, String type, Map<String, String> params) {
        EntryDefinition e = new EntryDefinition();
        e.id = id; e.type = type;
        e.params = params.isEmpty() ? null : new HashMap<>(params);
        return e;
    }

    private EntryHopDefinition entryHop(String from, String to, String eval) {
        EntryHopDefinition h = new EntryHopDefinition();
        h.from = from; h.to = to; h.evaluation = eval;
        return h;
    }
}
