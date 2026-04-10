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
}
