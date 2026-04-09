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
    // 6. FilterRows — new <condition> format with operator and value
    // -------------------------------------------------------------------------

    @Test
    void filterRows_conditionFormat_operatorAndValueExtracted() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>filter</name>
                <type>FilterRows</type>
                <condition>
                  <leftvalue><name>age</name></leftvalue>
                  <function>GT</function>
                  <rightvalue><value>18</value></rightvalue>
                </condition>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition sd = def.steps.get(0);

        assertEquals("FilterRows", sd.type);
        // Column name left as "age" — no upstream step with field schema in this KTR
        assertEquals("age",  sd.params.get("column"));
        assertEquals("GT",   sd.params.get("operator"));
        assertEquals("18",   sd.params.get("value"));
    }

    @Test
    void filterRows_columnNameResolvedToIndex_viaUpstreamFields() throws Exception {
        // Full pipeline: TextFileInput (with <fields>) → FilterRows
        // KtrParser should resolve "age" → "2" (0-based: id=0, name=1, age=2, city=3)
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              <step>
                <name>Read CSV</name>
                <type>TextFileInput</type>
                <file><name>C:/data/in.csv</name></file>
                <header>Y</header>
                <separator>,</separator>
                <fields>
                  <field><name>id</name><type>Integer</type></field>
                  <field><name>name</name><type>String</type></field>
                  <field><name>age</name><type>Integer</type></field>
                  <field><name>city</name><type>String</type></field>
                </fields>
              </step>
              <step>
                <name>Filter Rows</name>
                <type>FilterRows</type>
                <condition>
                  <leftvalue><name>age</name></leftvalue>
                  <function>GT</function>
                  <rightvalue><value>18</value></rightvalue>
                </condition>
              </step>
              <step>
                <name>Write CSV</name>
                <type>TextFileOutput</type>
                <file><name>C:/data/out.csv</name></file>
              </step>
              <hop><from>Read CSV</from><to>Filter Rows</to><enabled>Y</enabled></hop>
              <hop><from>Filter Rows</from><to>Write CSV</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        // TextFileInput should carry fieldNames + fieldTypes
        StepDefinition src = def.steps.get(0);
        assertEquals("id,name,age,city",                src.params.get("fieldNames"));
        assertEquals("Integer,String,Integer,String",   src.params.get("fieldTypes"));

        // FilterRows column "age" resolved to index 2
        StepDefinition filter = def.steps.get(1);
        assertEquals("2",          filter.params.get("column"));   // 0-based index of "age"
        assertEquals("GT",         filter.params.get("operator"));
        assertEquals("18",         filter.params.get("value"));
        // trueStep injected from the hop (no <send_true_to> in this KTR)
        assertEquals("Write CSV",  filter.params.get("trueStep"));
    }

    // -------------------------------------------------------------------------
    // 8. Unknown step type — uses default mapper (copies leaf text elements)
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
    // 9. Hop enabled=N → disabled
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
    // 10. Type name normalisation
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // 9. MergeJoin — full pipeline with field schema resolution
    // -------------------------------------------------------------------------

    @Test
    void mergeJoin_columnNamesResolvedToIndices() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>csv_join_transformation</name></info>
              <order>
                <hop><from>Read Customers CSV</from><to>Sort Customers</to><enabled>Y</enabled></hop>
                <hop><from>Read Orders CSV</from><to>Sort Orders</to><enabled>Y</enabled></hop>
                <hop><from>Sort Customers</from><to>Merge Join</to><enabled>Y</enabled></hop>
                <hop><from>Sort Orders</from><to>Merge Join</to><enabled>Y</enabled></hop>
              </order>
              <step>
                <name>Read Customers CSV</name>
                <type>CsvInput</type>
                <filename>/data/customers.csv</filename>
                <separator>,</separator>
                <header>Y</header>
                <fields>
                  <field><name>customer_id</name><type>Integer</type></field>
                  <field><name>name</name><type>String</type></field>
                </fields>
              </step>
              <step>
                <name>Read Orders CSV</name>
                <type>CsvInput</type>
                <filename>/data/orders.csv</filename>
                <separator>,</separator>
                <header>Y</header>
                <fields>
                  <field><name>order_id</name><type>Integer</type></field>
                  <field><name>customer_id</name><type>Integer</type></field>
                  <field><name>amount</name><type>Number</type></field>
                </fields>
              </step>
              <step>
                <name>Sort Customers</name>
                <type>SortRows</type>
                <sort_fields><field><name>customer_id</name><ascending>Y</ascending></field></sort_fields>
              </step>
              <step>
                <name>Sort Orders</name>
                <type>SortRows</type>
                <sort_fields><field><name>customer_id</name><ascending>Y</ascending></field></sort_fields>
              </step>
              <step>
                <name>Merge Join</name>
                <type>MergeJoin</type>
                <join_type>INNER</join_type>
                <step1>Sort Customers</step1>
                <step2>Sort Orders</step2>
                <keys_1><key>customer_id</key></keys_1>
                <keys_2><key>customer_id</key></keys_2>
              </step>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("csv_join_transformation", def.name);
        assertEquals(5, def.steps.size());
        assertEquals(4, def.hops.size());

        // Sort Customers: customer_id is index 0 in [customer_id, name]
        StepDefinition sortCust = def.steps.get(2);
        assertEquals("SortRows", sortCust.type);
        assertEquals("0", sortCust.params.get("columns"));

        // Sort Orders: customer_id is index 1 in [order_id, customer_id, amount]
        StepDefinition sortOrd = def.steps.get(3);
        assertEquals("SortRows", sortOrd.type);
        assertEquals("1", sortOrd.params.get("columns"));

        // Merge Join: left key = index 0 (customers), right key = index 1 (orders)
        StepDefinition join = def.steps.get(4);
        assertEquals("MergeJoin", join.type);
        assertEquals("INNER",          join.params.get("joinType"));
        assertEquals("Sort Customers", join.params.get("step1"));
        assertEquals("Sort Orders",    join.params.get("step2"));
        assertEquals("0",              join.params.get("leftColumns"));
        assertEquals("1",              join.params.get("rightColumns"));
    }

    // -------------------------------------------------------------------------
    // Type normalisation
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
