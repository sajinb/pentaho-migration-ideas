package com.pentaho.migration.converter;

import com.pentaho.migration.model.EntryDefinition;
import com.pentaho.migration.model.EntryHopDefinition;
import com.pentaho.migration.model.JobDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KjbParserTest {

    private final KjbParser parser = new KjbParser();

    private JobDefinition parse(String xml) throws Exception {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // Minimal KJB: Start → RunTransformation → Success
    // -------------------------------------------------------------------------

    @Test
    void basicJob_parsedCorrectly() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>daily_job</name>
              <entries>
                <entry>
                  <name>START</name>
                  <type>SPECIAL</type>
                  <start>Y</start>
                </entry>
                <entry>
                  <name>run_transform</name>
                  <type>TRANS</type>
                  <filename>transformations/customer_sort.ktr</filename>
                </entry>
                <entry>
                  <name>SUCCESS</name>
                  <type>SPECIAL</type>
                  <success>Y</success>
                </entry>
              </entries>
              <hops>
                <hop>
                  <from>START</from><to>run_transform</to>
                  <enabled>Y</enabled>
                  <unconditional>Y</unconditional>
                </hop>
                <hop>
                  <from>run_transform</from><to>SUCCESS</to>
                  <enabled>Y</enabled>
                  <evaluation>true</evaluation>
                  <unconditional>N</unconditional>
                </hop>
              </hops>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("daily_job", def.name);
        assertEquals(3, def.entries.size());
        assertEquals(2, def.hops.size());
    }

    // -------------------------------------------------------------------------
    // SPECIAL entry type resolution
    // -------------------------------------------------------------------------

    @Test
    void startEntry_parsedAsStartType() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>j</name>
              <entries>
                <entry><name>START</name><type>SPECIAL</type><start>Y</start></entry>
              </entries>
              <hops/>
            </job>
            """;

        JobDefinition def = parse(kjb);
        assertEquals("Start", def.entries.get(0).type);
    }

    @Test
    void successEntry_parsedAsSuccessType() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>j</name>
              <entries>
                <entry><name>SUCCESS</name><type>SPECIAL</type><success>Y</success></entry>
              </entries>
              <hops/>
            </job>
            """;

        assertEquals("Success", parse(kjb).entries.get(0).type);
    }

    @Test
    void abortEntry_parsedAsAbortType() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>j</name>
              <entries>
                <entry><name>ABORT</name><type>SPECIAL</type><abort>Y</abort></entry>
              </entries>
              <hops/>
            </job>
            """;

        assertEquals("Abort", parse(kjb).entries.get(0).type);
    }

    // -------------------------------------------------------------------------
    // RunTransformation: .ktr extension converted to .yaml in transformationPath
    // -------------------------------------------------------------------------

    @Test
    void transEntry_filenameExtensionConvertedToYaml() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>j</name>
              <entries>
                <entry>
                  <name>run</name>
                  <type>TRANS</type>
                  <filename>transformations/customer_sort.ktr</filename>
                </entry>
              </entries>
              <hops/>
            </job>
            """;

        EntryDefinition ed = parse(kjb).entries.get(0);
        assertEquals("RunTransformation", ed.type);
        assertEquals("transformations/customer_sort.yaml", ed.params.get("transformationPath"));
    }

    // -------------------------------------------------------------------------
    // Hop evaluation mapping
    // -------------------------------------------------------------------------

    @Test
    void hopEvaluation_unconditional() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job><name>j</name><entries/>
              <hops>
                <hop><from>a</from><to>b</to><enabled>Y</enabled>
                     <unconditional>Y</unconditional></hop>
              </hops>
            </job>
            """;

        EntryHopDefinition hop = parse(kjb).hops.get(0);
        assertEquals("unconditional", hop.evaluation);
    }

    @Test
    void hopEvaluation_success() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job><name>j</name><entries/>
              <hops>
                <hop><from>a</from><to>b</to><enabled>Y</enabled>
                     <evaluation>true</evaluation><unconditional>N</unconditional></hop>
              </hops>
            </job>
            """;

        assertEquals("success", parse(kjb).hops.get(0).evaluation);
    }

    @Test
    void hopEvaluation_failure() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job><name>j</name><entries/>
              <hops>
                <hop><from>a</from><to>b</to><enabled>Y</enabled>
                     <evaluation>false</evaluation><unconditional>N</unconditional></hop>
              </hops>
            </job>
            """;

        assertEquals("failure", parse(kjb).hops.get(0).evaluation);
    }

    @Test
    void hopEnabled_N_skipped() throws Exception {
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job><name>j</name><entries/>
              <hops>
                <hop><from>a</from><to>b</to><enabled>N</enabled></hop>
              </hops>
            </job>
            """;

        assertTrue(parse(kjb).hops.isEmpty(), "Disabled hops should be excluded");
    }

    // -------------------------------------------------------------------------
    // SUCCESS type used directly (non-SPECIAL) — regression for KJB format
    // -------------------------------------------------------------------------

    @Test
    void successTypeDirectly_normalised() throws Exception {
        // Pentaho standard uses <type>SPECIAL</type><success>Y</success>, but some KJBs
        // emit <type>SUCCESS</type> directly. Both must map to "Success".
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>main_job</name>
              <entries>
                <entry>
                  <name>START</name>
                  <type>SPECIAL</type>
                  <start>Y</start>
                </entry>
                <entry>
                  <name>Execute Join Transformation</name>
                  <type>TRANS</type>
                  <filename>${Internal.Entry.Current.Directory}/process_data.ktr</filename>
                </entry>
                <entry>
                  <name>Success</name>
                  <type>SUCCESS</type>
                </entry>
              </entries>
              <hops>
                <hop><from>START</from><to>Execute Join Transformation</to><enabled>Y</enabled></hop>
                <hop><from>Execute Join Transformation</from><to>Success</to><enabled>Y</enabled></hop>
              </hops>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("main_job", def.name);
        assertEquals(3, def.entries.size());
        assertEquals(2, def.hops.size());

        assertEquals("Start",             def.entries.get(0).type);
        assertEquals("RunTransformation", def.entries.get(1).type);
        // .ktr extension converted to .yaml
        assertEquals("${Internal.Entry.Current.Directory}/process_data.yaml",
                     def.entries.get(1).params.get("transformationPath"));
        // <type>SUCCESS</type> must normalise to "Success" (not "SUCCESS")
        assertEquals("Success",           def.entries.get(2).type);

        // Hops with no <evaluation>/<unconditional> default to "success"
        assertEquals("success", def.hops.get(0).evaluation);
        assertEquals("success", def.hops.get(1).evaluation);
    }

    // -------------------------------------------------------------------------
    // Master_Job.kjb: <type>Transformation</type> + <type>START</type>
    // -------------------------------------------------------------------------

    @Test
    void masterJob_transformationType_parsedCorrectly() throws Exception {
        // Real-world format: <type>START</type> (not SPECIAL) and <type>Transformation</type>
        // (not TRANS). Hops have no <evaluation> or <unconditional> — default to "success".
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job xmlns="http://www.pentaho.com/kettle/job/">
              <name>Master Job</name>
              <entries>
                <entry>
                  <name>Start</name>
                  <type>START</type>
                </entry>
                <entry>
                  <name>Run Transform1</name>
                  <type>Transformation</type>
                  <filename>/path/to/Transform1.ktr</filename>
                </entry>
                <entry>
                  <name>Run Transform2</name>
                  <type>Transformation</type>
                  <filename>/path/to/Transform2.ktr</filename>
                </entry>
                <entry>
                  <name>Run Transform3</name>
                  <type>Transformation</type>
                  <filename>/path/to/Transform3.ktr</filename>
                </entry>
              </entries>
              <hops>
                <hop><from>Start</from><to>Run Transform1</to><enabled>Y</enabled></hop>
                <hop><from>Run Transform1</from><to>Run Transform2</to><enabled>Y</enabled></hop>
                <hop><from>Run Transform2</from><to>Run Transform3</to><enabled>Y</enabled></hop>
              </hops>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("Master Job", def.name);
        assertEquals(4, def.entries.size());
        assertEquals(3, def.hops.size());

        // <type>START</type> → "Start"
        assertEquals("Start", def.entries.get(0).type);

        // <type>Transformation</type> → "RunTransformation" + .ktr → .yaml
        assertEquals("RunTransformation", def.entries.get(1).type);
        assertEquals("/path/to/Transform1.yaml", def.entries.get(1).params.get("transformationPath"));
        assertEquals("RunTransformation", def.entries.get(2).type);
        assertEquals("/path/to/Transform2.yaml", def.entries.get(2).params.get("transformationPath"));
        assertEquals("RunTransformation", def.entries.get(3).type);
        assertEquals("/path/to/Transform3.yaml", def.entries.get(3).params.get("transformationPath"));

        // Hops: no <evaluation>/<unconditional> → defaults to "success"
        assertEquals("Start",        def.hops.get(0).from);
        assertEquals("Run Transform1", def.hops.get(0).to);
        assertEquals("Run Transform2", def.hops.get(1).to);
        assertEquals("Run Transform3", def.hops.get(2).to);
    }

    // -------------------------------------------------------------------------
    // NonLinear_Orchestration_Job: fan-out + fan-in + independent branch
    // -------------------------------------------------------------------------

    @Test
    void nonLinearOrchestration_fanOutFanIn_parsedCorrectly() throws Exception {
        // Topology: Start → A (parallel), Start → B (parallel), Start → D (independent)
        //           A → C (fan-in), B → C (fan-in)
        // The parser just captures entries and hops; the engine handles execution semantics.
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job xmlns="http://www.pentaho.com/kettle/job/">
              <name>NonLinear_Orchestration_Job</name>
              <entries>
                <entry><name>Start</name><type>START</type></entry>
                <entry>
                  <name>Run TransformA</name>
                  <type>Transformation</type>
                  <filename>/path/to/TransformA.ktr</filename>
                </entry>
                <entry>
                  <name>Run TransformB</name>
                  <type>Transformation</type>
                  <filename>/path/to/TransformB.ktr</filename>
                </entry>
                <entry>
                  <name>Run TransformC</name>
                  <type>Transformation</type>
                  <filename>/path/to/TransformC.ktr</filename>
                </entry>
                <entry>
                  <name>Run TransformD</name>
                  <type>Transformation</type>
                  <filename>/path/to/TransformD.ktr</filename>
                </entry>
              </entries>
              <hops>
                <hop><from>Start</from><to>Run TransformA</to><enabled>Y</enabled></hop>
                <hop><from>Start</from><to>Run TransformB</to><enabled>Y</enabled></hop>
                <hop><from>Run TransformA</from><to>Run TransformC</to><enabled>Y</enabled></hop>
                <hop><from>Run TransformB</from><to>Run TransformC</to><enabled>Y</enabled></hop>
                <hop><from>Start</from><to>Run TransformD</to><enabled>Y</enabled></hop>
              </hops>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("NonLinear_Orchestration_Job", def.name);
        assertEquals(5, def.entries.size());
        assertEquals(5, def.hops.size());

        // All transformation entries normalised correctly
        assertEquals("Start",             def.entries.get(0).type);
        assertEquals("RunTransformation", def.entries.get(1).type);
        assertEquals("/path/to/TransformA.yaml", def.entries.get(1).params.get("transformationPath"));
        assertEquals("RunTransformation", def.entries.get(2).type);
        assertEquals("/path/to/TransformB.yaml", def.entries.get(2).params.get("transformationPath"));
        assertEquals("RunTransformation", def.entries.get(3).type);
        assertEquals("/path/to/TransformC.yaml", def.entries.get(3).params.get("transformationPath"));
        assertEquals("RunTransformation", def.entries.get(4).type);
        assertEquals("/path/to/TransformD.yaml", def.entries.get(4).params.get("transformationPath"));

        // Fan-out from Start: 3 outgoing hops
        long fromStart = def.hops.stream().filter(h -> "Start".equals(h.from)).count();
        assertEquals(3, fromStart, "Start should have 3 outgoing hops (A, B, D)");

        // Fan-in to C: 2 incoming hops
        long toC = def.hops.stream().filter(h -> "Run TransformC".equals(h.to)).count();
        assertEquals(2, toC, "TransformC should have 2 incoming hops (from A and from B)");

        // D is independent: 1 incoming hop (from Start), no outgoing hops
        long fromD = def.hops.stream().filter(h -> "Run TransformD".equals(h.from)).count();
        assertEquals(0, fromD, "TransformD should have no outgoing hops");
    }

    // -------------------------------------------------------------------------
    // daily_transaction_job: CHECK_FILE_EXISTS, Shell, Mail; entries/hops as
    // direct <job> children (no <entries>/<hops> wrapper); failure hops
    // -------------------------------------------------------------------------

    @Test
    void dailyTransactionJob_parsedCorrectly() throws Exception {
        // Real-world KJB format:
        //  - entries/hops are direct children of <job> (no wrapper elements)
        //  - CHECK_FILE_EXISTS → FileExists with filePath param
        //  - Shell → ExecProcess with command param (from <script> CDATA)
        //  - Mail → Mail with to + subject params
        //  - <evaluation>FALSE</evaluation> (uppercase) → "failure"
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>daily_transaction_job</name>

              <entry>
                <name>START</name>
                <type>SPECIAL</type>
                <start>Y</start>
              </entry>

              <entry>
                <name>Check Input Files</name>
                <type>CHECK_FILE_EXISTS</type>
                <filename>C:/test/input/input.csv</filename>
                <fail_if_no_file>Y</fail_if_no_file>
              </entry>

              <entry>
                <name>Run Daily Transform</name>
                <type>TRANSFORMATION</type>
                <filename>/opt/etl/daily_transform.ktr</filename>
              </entry>

              <entry>
                <name>Archive Input Files</name>
                <type>Shell</type>
                <script><![CDATA[move C:\\test\\input\\input.csv C:\\test\\archive\\]]></script>
              </entry>

              <entry>
                <name>Send Failure Mail</name>
                <type>Mail</type>
                <destination>etl-alerts@company.com</destination>
                <subject>Daily Transaction Job Failed</subject>
              </entry>

              <hop>
                <from>START</from><to>Check Input Files</to>
                <enabled>Y</enabled><unconditional>Y</unconditional>
              </hop>
              <hop>
                <from>Check Input Files</from><to>Run Daily Transform</to>
                <enabled>Y</enabled>
                <evaluation>true</evaluation><unconditional>N</unconditional>
              </hop>
              <hop>
                <from>Check Input Files</from><to>Send Failure Mail</to>
                <enabled>Y</enabled>
                <evaluation>FALSE</evaluation><unconditional>N</unconditional>
              </hop>
              <hop>
                <from>Run Daily Transform</from><to>Archive Input Files</to>
                <enabled>Y</enabled>
                <evaluation>true</evaluation><unconditional>N</unconditional>
              </hop>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("daily_transaction_job", def.name);
        assertEquals(5, def.entries.size());
        assertEquals(4, def.hops.size());

        // START
        EntryDefinition start = def.entries.get(0);
        assertEquals("Start", start.type);

        // CHECK_FILE_EXISTS → FileExists
        EntryDefinition checkFile = def.entries.get(1);
        assertEquals("FileExists", checkFile.type);
        assertEquals("C:/test/input/input.csv", checkFile.params.get("filePath"));
        assertEquals("true", checkFile.params.get("failIfNoFile"));

        // TRANSFORMATION → RunTransformation with .ktr → .yaml
        EntryDefinition runTransform = def.entries.get(2);
        assertEquals("RunTransformation", runTransform.type);
        assertEquals("/opt/etl/daily_transform.yaml", runTransform.params.get("transformationPath"));

        // Shell → ExecProcess with script CDATA as command
        EntryDefinition archive = def.entries.get(3);
        assertEquals("ExecProcess", archive.type);
        assertNotNull(archive.params.get("command"), "Shell script should be mapped to command param");
        assertTrue(archive.params.get("command").contains("move"), "command should contain the shell script content");

        // Mail → Mail with to + subject
        EntryDefinition mail = def.entries.get(4);
        assertEquals("Mail", mail.type);
        assertEquals("etl-alerts@company.com", mail.params.get("to"));
        assertEquals("Daily Transaction Job Failed", mail.params.get("subject"));

        // Hops: unconditional, success, failure (uppercase FALSE), success
        EntryHopDefinition startHop = def.hops.get(0);
        assertEquals("START", startHop.from);
        assertEquals("Check Input Files", startHop.to);
        assertEquals("unconditional", startHop.evaluation);

        EntryHopDefinition successHop = def.hops.get(1);
        assertEquals("Check Input Files", successHop.from);
        assertEquals("Run Daily Transform", successHop.to);
        assertEquals("success", successHop.evaluation);

        // <evaluation>FALSE</evaluation> (uppercase) must resolve to "failure"
        EntryHopDefinition failureHop = def.hops.get(2);
        assertEquals("Check Input Files", failureHop.from);
        assertEquals("Send Failure Mail", failureHop.to);
        assertEquals("failure", failureHop.evaluation);

        EntryHopDefinition archiveHop = def.hops.get(3);
        assertEquals("Run Daily Transform", archiveHop.from);
        assertEquals("Archive Input Files", archiveHop.to);
        assertEquals("success", archiveHop.evaluation);
    }

    // -------------------------------------------------------------------------
    // daily_parallel_aggregation_job:
    //  - BlockUntilStepsFinish explicit sync barrier
    //  - wait_for_finish=N on parallel KTR entries
    //  - fan-out (Load → A, B in parallel) + fan-in (A, B → Wait)
    // -------------------------------------------------------------------------

    @Test
    void dailyParallelAggregationJob_parsedCorrectly() throws Exception {
        // Key patterns:
        //  BlockUntilStepsFinish → Dummy: our engine's CompletableFuture.allOf() fan-in
        //  already provides this synchronization from hop topology alone.
        //  wait_for_finish=N on RunTransformation entries: in our engine parallel
        //  execution comes from fan-out topology, not from this flag; it has no effect.
        String kjb = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job xmlns="http://www.pentaho.com/kettle/job/">
              <name>daily_parallel_aggregation_job</name>

              <entry>
                <name>START</name>
                <type>START</type>
                <start>Y</start>
                <enabled>Y</enabled>
              </entry>

              <entry>
                <name>Load And Validate Data</name>
                <type>Transformation</type>
                <filename>load_validate.ktr</filename>
                <wait_for_finish>Y</wait_for_finish>
                <enabled>Y</enabled>
              </entry>

              <entry>
                <name>Customer Aggregation</name>
                <type>Transformation</type>
                <filename>customer_aggregate.ktr</filename>
                <wait_for_finish>N</wait_for_finish>
                <enabled>Y</enabled>
              </entry>

              <entry>
                <name>Region Aggregation</name>
                <type>Transformation</type>
                <filename>region_aggregate.ktr</filename>
                <wait_for_finish>N</wait_for_finish>
                <enabled>Y</enabled>
              </entry>

              <entry>
                <name>Wait For Aggregations</name>
                <type>BlockUntilStepsFinish</type>
                <stepnames>
                  <stepname>Customer Aggregation</stepname>
                  <stepname>Region Aggregation</stepname>
                </stepnames>
                <enabled>Y</enabled>
              </entry>

              <entry>
                <name>Reconciliation</name>
                <type>Transformation</type>
                <filename>reconciliation.ktr</filename>
                <wait_for_finish>Y</wait_for_finish>
                <enabled>Y</enabled>
              </entry>

              <hop><from>START</from><to>Load And Validate Data</to><enabled>Y</enabled></hop>
              <hop><from>Load And Validate Data</from><to>Customer Aggregation</to><enabled>Y</enabled></hop>
              <hop><from>Load And Validate Data</from><to>Region Aggregation</to><enabled>Y</enabled></hop>
              <hop><from>Customer Aggregation</from><to>Wait For Aggregations</to><enabled>Y</enabled></hop>
              <hop><from>Region Aggregation</from><to>Wait For Aggregations</to><enabled>Y</enabled></hop>
              <hop><from>Wait For Aggregations</from><to>Reconciliation</to><enabled>Y</enabled></hop>
            </job>
            """;

        JobDefinition def = parse(kjb);

        assertEquals("daily_parallel_aggregation_job", def.name);
        assertEquals(6, def.entries.size());
        assertEquals(6, def.hops.size());

        // START
        assertEquals("Start", def.entries.get(0).type);

        // Load And Validate Data — sequential (wait_for_finish=Y doesn't affect our model)
        EntryDefinition load = def.entries.get(1);
        assertEquals("RunTransformation", load.type);
        assertEquals("load_validate.yaml", load.params.get("transformationPath"));

        // Customer Aggregation — parallel (wait_for_finish=N)
        EntryDefinition custAgg = def.entries.get(2);
        assertEquals("RunTransformation", custAgg.type);
        assertEquals("customer_aggregate.yaml", custAgg.params.get("transformationPath"));

        // Region Aggregation — parallel (wait_for_finish=N)
        EntryDefinition regionAgg = def.entries.get(3);
        assertEquals("RunTransformation", regionAgg.type);
        assertEquals("region_aggregate.yaml", regionAgg.params.get("transformationPath"));

        // BlockUntilStepsFinish → Dummy (engine fan-in handles synchronization via allOf())
        EntryDefinition waitEntry = def.entries.get(4);
        assertEquals("Wait For Aggregations", waitEntry.id);
        assertEquals("Dummy", waitEntry.type);

        // Reconciliation
        EntryDefinition recon = def.entries.get(5);
        assertEquals("RunTransformation", recon.type);
        assertEquals("reconciliation.yaml", recon.params.get("transformationPath"));

        // Fan-out from Load And Validate Data: 2 outgoing hops (Customer, Region)
        long fromLoad = def.hops.stream()
                .filter(h -> "Load And Validate Data".equals(h.from)).count();
        assertEquals(2, fromLoad, "Load And Validate Data should have 2 outgoing hops");

        // Fan-in to Wait For Aggregations: 2 incoming hops
        long toWait = def.hops.stream()
                .filter(h -> "Wait For Aggregations".equals(h.to)).count();
        assertEquals(2, toWait, "Wait For Aggregations should have 2 incoming hops");

        // All hops with no evaluation/unconditional default to "success"
        assertTrue(def.hops.stream().allMatch(h -> "success".equals(h.evaluation)),
                "All hops should default to success evaluation");
    }
}
