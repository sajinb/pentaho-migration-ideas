package com.pentaho.migration.converter;

import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class KtrParserTest {

    private final KtrParser parser = new KtrParser();

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private TransformationDefinition parse(String xml) throws Exception {
        return parser.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    // -------------------------------------------------------------------------
    // 1. Linear transformation — name, steps, hops
    // -------------------------------------------------------------------------

    @Test
    void linearTransformation_parsedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>my_pipeline</name></info>
              <step>
                <name>read_csv</name>
                <type>CSVInput</type>
                <filename>/data/in.csv</filename>
                <header>Y</header>
              </step>
              <step>
                <name>write_csv</name>
                <type>TextFileOutput</type>
                <file><name>/data/out.csv</name></file>
              </step>
              <order>
                <hop><from>read_csv</from><to>write_csv</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("my_pipeline", def.name);
        assertEquals(2, def.steps.size());
        assertEquals(1, def.hops.size());

        StepDefinition src = def.steps.get(0);
        assertEquals("read_csv",  src.id);
        assertEquals("CsvInput",  src.type);  // normalised from CSVInput

        StepDefinition sink = def.steps.get(1);
        assertEquals("write_csv",      sink.id);
        assertEquals("TextFileOutput", sink.type);

        HopDefinition hop = def.hops.get(0);
        assertEquals("read_csv",  hop.from);
        assertEquals("write_csv", hop.to);
        assertTrue(hop.enabled);
    }

    // -------------------------------------------------------------------------
    // 2. CsvInput param mapping
    // -------------------------------------------------------------------------

    @Test
    void csvInputParams_mappedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>src</name>
                <type>CSVInput</type>
                <filename>/path/to/file.csv</filename>
                <header>N</header>
                <separator>;</separator>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("CsvInput",         sd.type);
        assertEquals("/path/to/file.csv", sd.params.get("filePath"));
        assertEquals("false",             sd.params.get("hasHeader"));
        assertEquals(";",                 sd.params.get("separator"));
    }

    // -------------------------------------------------------------------------
    // 3. TextFileOutput param mapping
    // -------------------------------------------------------------------------

    @Test
    void textFileOutputParams_mappedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>out</name>
                <type>TextFileOutput</type>
                <file>
                  <name>/out/result.csv</name>
                  <separator>,</separator>
                  <header>Y</header>
                </file>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("/out/result.csv", sd.params.get("filePath"));
        assertEquals("true",            sd.params.get("writeHeader"));
    }

    // -------------------------------------------------------------------------
    // 4. SortRows — field names extracted
    // -------------------------------------------------------------------------

    @Test
    void sortRowsParams_columnNamesMapped() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>sort</name>
                <type>SortRows</type>
                <fields>
                  <field><name>last_name</name><ascending>Y</ascending></field>
                  <field><name>first_name</name><ascending>Y</ascending></field>
                </fields>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("SortRows",              sd.type);
        assertEquals("last_name,first_name",  sd.params.get("columns"));
    }

    // -------------------------------------------------------------------------
    // 5. FilterRows — trueStep / falseStep
    // -------------------------------------------------------------------------

    @Test
    void filterRowsParams_mappedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>filter</name>
                <type>FilterRows</type>
                <send_true_to>active_out</send_true_to>
                <send_false_to>inactive_out</send_false_to>
                <compare>
                  <fieldname>status</fieldname>
                  <value>ACTIVE</value>
                </compare>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("FilterRows",    sd.type);
        assertEquals("active_out",    sd.params.get("trueStep"));
        assertEquals("inactive_out",  sd.params.get("falseStep"));
        assertEquals("status",        sd.params.get("column"));
        assertEquals("ACTIVE",        sd.params.get("value"));
    }

    // -------------------------------------------------------------------------
    // 6. Unknown step type — uses default mapper (copies leaf text elements)
    // -------------------------------------------------------------------------

    @Test
    void unknownStepType_usesDefaultMapper() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>my_step</name>
                <type>CustomXYZ</type>
                <myParam>hello</myParam>
                <anotherParam>world</anotherParam>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("CustomXYZ", sd.type);
        assertEquals("hello",     sd.params.get("myParam"));
        assertEquals("world",     sd.params.get("anotherParam"));
    }

    // -------------------------------------------------------------------------
    // 7. Hop enabled=N → disabled
    // -------------------------------------------------------------------------

    @Test
    void hopEnabled_N_parsedAsDisabled() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step><name>a</name><type>Dummy (do nothing)</type></step>
              <step><name>b</name><type>Dummy (do nothing)</type></step>
              <order>
                <hop><from>a</from><to>b</to><enabled>N</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertFalse(def.hops.get(0).enabled);
        // Dummy type name normalised
        assertEquals("Dummy", def.steps.get(0).type);
    }

    // -------------------------------------------------------------------------
    // 8. Type name normalisation
    // -------------------------------------------------------------------------

    @Test
    void typeNormalisation_knownTypes() {
        assertEquals("CsvInput",         KtrParser.normalizeType("CSVInput"));
        assertEquals("CsvInput",         KtrParser.normalizeType("CsvInput"));
        assertEquals("Dummy",            KtrParser.normalizeType("Dummy (do nothing)"));
        assertEquals("Mapping",          KtrParser.normalizeType("Mapping (Sub-transformation)"));
        assertEquals("MergeRows",        KtrParser.normalizeType("MergeRows (diff)"));
        assertEquals("Append",           KtrParser.normalizeType("AppendStreams"));
    }

    @Test
    void typeNormalisation_unknownPassesThrough() {
        assertEquals("SomeCustomStep",   KtrParser.normalizeType("SomeCustomStep"));
        assertEquals("Unknown",          KtrParser.normalizeType(null));
    }
}
