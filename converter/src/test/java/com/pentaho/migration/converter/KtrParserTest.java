package com.pentaho.migration.converter;

import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    // -------------------------------------------------------------------------
    // Multi-source + conditional + MergeJoin without step1/step2
    // -------------------------------------------------------------------------

    @Test
    void multiSourceConditionalKtr_parsedCorrectly() throws Exception {
        // This exercises:
        //  - CsvInput with <file><name> format (not <filename>)
        //  - MergeJoin with <key_fields1>/<key><name> format and NO <step1>/<step2>
        //  - UniqueRows → Unique type mapping
        //  - FilterRows with <condition><condition> nesting + => operator + <true_step>/<false_step>
        //  - ModifiedJavaScriptValue → ScriptValueMod mapping
        //  - Hops as direct root children (no <order> wrapper)
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation xmlns="http://www.pentaho.com/kettle/transformation/">
              <info><name>Complex_CSV_MultiSource_Conditional</name></info>
              <step>
                <name>CSV Input A</name><type>CsvInput</type>
                <file><name>/path/users.csv</name></file>
                <content><header>Y</header><separator>,</separator></content>
                <fields>
                  <field><name>user_id</name></field>
                  <field><name>email</name></field>
                  <field><name>country</name></field>
                </fields>
              </step>
              <step>
                <name>CSV Input B</name><type>CsvInput</type>
                <file><name>/path/transactions.csv</name></file>
                <content><header>Y</header><separator>,</separator></content>
                <fields>
                  <field><name>transaction_id</name></field>
                  <field><name>user_id</name></field>
                  <field><name>amount</name></field>
                </fields>
              </step>
              <step>
                <name>CSV Input C</name><type>CsvInput</type>
                <file><name>/path/blacklisted.csv</name></file>
                <content><header>Y</header></content>
                <fields>
                  <field><name>blacklisted_email</name></field>
                </fields>
              </step>
              <step>
                <name>Filter High Transactions</name><type>FilterRows</type>
                <condition>
                  <condition>
                    <leftvalue>amount</leftvalue>
                    <function>=&gt;</function>
                    <rightvalue>100</rightvalue>
                  </condition>
                </condition>
                <true_step>Join Users and Transactions</true_step>
                <false_step>Discard Low Transactions</false_step>
              </step>
              <step><name>Discard Low Transactions</name><type>Dummy</type></step>
              <step>
                <name>Join Users and Transactions</name><type>MergeJoin</type>
                <join_type>INNER</join_type>
                <key_fields1><key><name>user_id</name></key></key_fields1>
                <key_fields2><key><name>user_id</name></key></key_fields2>
              </step>
              <step><name>Remove Duplicate Emails</name><type>UniqueRows</type></step>
              <step>
                <name>Tag User Country</name><type>ModifiedJavaScriptValue</type>
                <script><![CDATA[var tag = (country == "USA") ? "Domestic" : "International";]]></script>
                <fields>
                  <field><name>tag</name></field>
                </fields>
              </step>
              <step>
                <name>Merge User-Blacklist</name><type>MergeJoin</type>
                <join_type>LEFT OUTER</join_type>
                <key_fields1><key><name>email</name></key></key_fields1>
                <key_fields2><key><name>blacklisted_email</name></key></key_fields2>
              </step>
              <step>
                <name>Clean Users Output</name><type>TextFileOutput</type>
                <file><name>/path/clean.csv</name></file>
              </step>
              <hop><from>CSV Input B</from><to>Filter High Transactions</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Transactions</from><to>Join Users and Transactions</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Transactions</from><to>Discard Low Transactions</to><enabled>Y</enabled></hop>
              <hop><from>CSV Input A</from><to>Join Users and Transactions</to><enabled>Y</enabled></hop>
              <hop><from>Join Users and Transactions</from><to>Remove Duplicate Emails</to><enabled>Y</enabled></hop>
              <hop><from>Remove Duplicate Emails</from><to>Tag User Country</to><enabled>Y</enabled></hop>
              <hop><from>Tag User Country</from><to>Merge User-Blacklist</to><enabled>Y</enabled></hop>
              <hop><from>CSV Input C</from><to>Merge User-Blacklist</to><enabled>Y</enabled></hop>
              <hop><from>Merge User-Blacklist</from><to>Clean Users Output</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("Complex_CSV_MultiSource_Conditional", def.name);
        assertEquals(10, def.steps.size());
        assertEquals(9,  def.hops.size());

        // CsvInput <file><name> format → filePath extracted correctly
        StepDefinition csvA = def.steps.stream().filter(s -> "CSV Input A".equals(s.id)).findFirst().orElseThrow();
        assertEquals("CsvInput", csvA.type);
        assertEquals("/path/users.csv", csvA.params.get("filePath"));
        assertEquals("true", csvA.params.get("hasHeader"));

        // UniqueRows → normalized to Unique
        StepDefinition unique = def.steps.stream().filter(s -> "Remove Duplicate Emails".equals(s.id)).findFirst().orElseThrow();
        assertEquals("Unique", unique.type);

        // ScriptValueMod mapped from ModifiedJavaScriptValue
        StepDefinition js = def.steps.stream().filter(s -> "Tag User Country".equals(s.id)).findFirst().orElseThrow();
        assertEquals("ScriptValueMod", js.type);
        assertNotNull(js.params.get("script"), "script param should be extracted from <script> CDATA");
        assertEquals("tag", js.params.get("outputFields"));

        // FilterRows: operator normalized, routing targets extracted
        StepDefinition filter = def.steps.stream().filter(s -> "Filter High Transactions".equals(s.id)).findFirst().orElseThrow();
        assertEquals("FilterRows", filter.type);
        assertEquals("GTE",                           filter.params.get("operator"));
        assertEquals("100",                           filter.params.get("value"));
        assertEquals("Join Users and Transactions",   filter.params.get("trueStep"));
        assertEquals("Discard Low Transactions",      filter.params.get("falseStep"));

        // MergeJoin key_fields1/key_fields2 format with inferred step1/step2
        StepDefinition join = def.steps.stream().filter(s -> "Join Users and Transactions".equals(s.id)).findFirst().orElseThrow();
        assertEquals("MergeJoin", join.type);
        assertNotNull(join.params.get("step1"), "step1 should be inferred from hop topology");
        assertNotNull(join.params.get("step2"), "step2 should be inferred from hop topology");
        assertNotNull(join.params.get("leftColumns"),  "left key columns should be resolved");
        assertNotNull(join.params.get("rightColumns"), "right key columns should be resolved");

        // Second MergeJoin: LEFT OUTER join, different keys per side
        StepDefinition blacklistJoin = def.steps.stream().filter(s -> "Merge User-Blacklist".equals(s.id)).findFirst().orElseThrow();
        assertEquals("LEFT OUTER", blacklistJoin.params.get("joinType"));
    }

    // -------------------------------------------------------------------------
    // SwitchCase dynamic routing
    // -------------------------------------------------------------------------

    @Test
    void switchCase_dynamicRoutingKtr_parsedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation xmlns="http://www.pentaho.com/kettle/transformation/">
              <info><name>Dynamic_Routing_By_Status</name></info>
              <step>
                <name>CSV Input</name><type>CsvInput</type>
                <file><name>/path/input_status.csv</name></file>
                <content><separator>,</separator><header>Y</header></content>
                <fields>
                  <field><name>id</name></field>
                  <field><name>name</name></field>
                  <field><name>status</name></field>
                </fields>
              </step>
              <step>
                <name>Status Switch</name><type>SwitchCase</type>
                <field_name>status</field_name>
                <case><value>New</value><target>New Status Output</target></case>
                <case><value>In Progress</value><target>In Progress Output</target></case>
                <case><value>Closed</value><target>Closed Status Output</target></case>
              </step>
              <step>
                <name>New Status Output</name><type>TextFileOutput</type>
                <file><name>/path/output_new.csv</name></file>
              </step>
              <step>
                <name>In Progress Output</name><type>TextFileOutput</type>
                <file><name>/path/output_inprogress.csv</name></file>
              </step>
              <step>
                <name>Closed Status Output</name><type>TextFileOutput</type>
                <file><name>/path/output_closed.csv</name></file>
              </step>
              <hop><from>CSV Input</from><to>Status Switch</to><enabled>Y</enabled></hop>
              <hop><from>Status Switch</from><to>New Status Output</to><enabled>Y</enabled></hop>
              <hop><from>Status Switch</from><to>In Progress Output</to><enabled>Y</enabled></hop>
              <hop><from>Status Switch</from><to>Closed Status Output</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("Dynamic_Routing_By_Status", def.name);
        assertEquals(5, def.steps.size());
        assertEquals(4, def.hops.size());

        // CsvInput: <file><name> format + schema
        StepDefinition csv = def.steps.get(0);
        assertEquals("CsvInput",              csv.type);
        assertEquals("/path/input_status.csv", csv.params.get("filePath"));

        // SwitchCase: field resolved to column index 2, all case.VALUE entries present
        StepDefinition sw = def.steps.get(1);
        assertEquals("SwitchCase", sw.type);
        assertEquals("2",                     sw.params.get("column"),
                "status is the 3rd field (index 2) → column should be 2");
        assertEquals("New Status Output",     sw.params.get("case.New"));
        assertEquals("In Progress Output",    sw.params.get("case.In Progress"));
        assertEquals("Closed Status Output",  sw.params.get("case.Closed"));
        assertNull(sw.params.get("defaultStep"), "no default case defined");

        // Three TextFileOutput sinks
        long outputCount = def.steps.stream()
                .filter(s -> "TextFileOutput".equals(s.type)).count();
        assertEquals(3, outputCount);
    }

    @Test
    void complexConditionalRouting_multiFields_parsedCorrectly() throws Exception {
        // KTR: CsvInput → FilterRows (priority=High AND status=Pending) → FilterRows (status=Closed AND region=US)
        //      → SwitchCase (status) → TextFileOutput (x4)
        // Exercises: compound <condition> block, <true_step>/<false_step>, SwitchCase with two named cases.
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation xmlns="http://www.pentaho.com/kettle/transformation/">
              <info>
                <name>Complex_Conditional_Routing_MultiFields</name>
              </info>
              <step>
                <name>CSV Input</name>
                <type>CsvInput</type>
                <file>
                  <name>/path/to/csv/input_complex.csv</name>
                </file>
                <content>
                  <separator>,</separator>
                  <enclosure>"</enclosure>
                  <header>Y</header>
                </content>
                <fields>
                  <field><name>id</name><type>Integer</type></field>
                  <field><name>status</name><type>String</type></field>
                  <field><name>priority</name><type>String</type></field>
                  <field><name>region</name><type>String</type></field>
                </fields>
              </step>
              <step>
                <name>Filter High Priority Pending</name>
                <type>FilterRows</type>
                <condition>
                  <condition>
                    <leftvalue>priority</leftvalue>
                    <function>=</function>
                    <rightvalue>High</rightvalue>
                    <valuetype>String</valuetype>
                  </condition>
                  <condition_type>AND</condition_type>
                  <next_condition>
                    <leftvalue>status</leftvalue>
                    <function>=</function>
                    <rightvalue>Pending</rightvalue>
                    <valuetype>String</valuetype>
                  </next_condition>
                </condition>
                <true_step>High Priority Pending Output</true_step>
                <false_step>Filter Closed US</false_step>
              </step>
              <step>
                <name>Filter Closed US</name>
                <type>FilterRows</type>
                <condition>
                  <condition>
                    <leftvalue>status</leftvalue>
                    <function>=</function>
                    <rightvalue>Closed</rightvalue>
                    <valuetype>String</valuetype>
                  </condition>
                  <condition_type>AND</condition_type>
                  <next_condition>
                    <leftvalue>region</leftvalue>
                    <function>=</function>
                    <rightvalue>US</rightvalue>
                    <valuetype>String</valuetype>
                  </next_condition>
                </condition>
                <true_step>Closed US Output</true_step>
                <false_step>Status Switch</false_step>
              </step>
              <step>
                <name>Status Switch</name>
                <type>SwitchCase</type>
                <field_name>status</field_name>
                <case><value>In Progress</value><target>In Progress Output</target></case>
                <case><value>On Hold</value><target>On Hold Output</target></case>
              </step>
              <step>
                <name>High Priority Pending Output</name><type>TextFileOutput</type>
                <file><name>/path/to/csv/output_high_priority_pending.csv</name><separator>,</separator></file>
              </step>
              <step>
                <name>Closed US Output</name><type>TextFileOutput</type>
                <file><name>/path/to/csv/output_closed_us.csv</name><separator>,</separator></file>
              </step>
              <step>
                <name>In Progress Output</name><type>TextFileOutput</type>
                <file><name>/path/to/csv/output_in_progress.csv</name><separator>,</separator></file>
              </step>
              <step>
                <name>On Hold Output</name><type>TextFileOutput</type>
                <file><name>/path/to/csv/output_on_hold.csv</name><separator>,</separator></file>
              </step>
              <hop><from>CSV Input</from><to>Filter High Priority Pending</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Priority Pending</from><to>High Priority Pending Output</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Priority Pending</from><to>Filter Closed US</to><enabled>Y</enabled></hop>
              <hop><from>Filter Closed US</from><to>Closed US Output</to><enabled>Y</enabled></hop>
              <hop><from>Filter Closed US</from><to>Status Switch</to><enabled>Y</enabled></hop>
              <hop><from>Status Switch</from><to>In Progress Output</to><enabled>Y</enabled></hop>
              <hop><from>Status Switch</from><to>On Hold Output</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("Complex_Conditional_Routing_MultiFields", def.name);
        assertEquals(8, def.steps.size());
        assertEquals(7, def.hops.size());

        // CsvInput: <file><name> format, schema [id, status, priority, region]
        StepDefinition csv = def.steps.stream().filter(s -> "CSV Input".equals(s.id)).findFirst().orElseThrow();
        assertEquals("CsvInput", csv.type);
        assertEquals("/path/to/csv/input_complex.csv", csv.params.get("filePath"));
        assertEquals("true", csv.params.get("hasHeader"));

        // Filter High Priority Pending: picks up FIRST condition (priority=High), explicit routing
        StepDefinition f1 = def.steps.stream()
                .filter(s -> "Filter High Priority Pending".equals(s.id)).findFirst().orElseThrow();
        assertEquals("FilterRows", f1.type);
        assertEquals("2",                           f1.params.get("column"),
                "priority is index 2 in [id, status, priority, region]");
        assertEquals("EQ",                          f1.params.get("operator"));
        assertEquals("High",                        f1.params.get("value"));
        assertEquals("High Priority Pending Output", f1.params.get("trueStep"));
        assertEquals("Filter Closed US",            f1.params.get("falseStep"));

        // Filter Closed US: picks up FIRST condition (status=Closed), explicit routing
        StepDefinition f2 = def.steps.stream()
                .filter(s -> "Filter Closed US".equals(s.id)).findFirst().orElseThrow();
        assertEquals("FilterRows", f2.type);
        assertEquals("1",              f2.params.get("column"),
                "status is index 1 in [id, status, priority, region]");
        assertEquals("EQ",             f2.params.get("operator"));
        assertEquals("Closed",         f2.params.get("value"));
        assertEquals("Closed US Output", f2.params.get("trueStep"));
        assertEquals("Status Switch",  f2.params.get("falseStep"));

        // SwitchCase: status is index 1, two named cases, no default
        StepDefinition sw = def.steps.stream()
                .filter(s -> "Status Switch".equals(s.id)).findFirst().orElseThrow();
        assertEquals("SwitchCase", sw.type);
        assertEquals("1",                   sw.params.get("column"),
                "status is index 1 in [id, status, priority, region]");
        assertEquals("In Progress Output",  sw.params.get("case.In Progress"));
        assertEquals("On Hold Output",      sw.params.get("case.On Hold"));
        assertNull(sw.params.get("defaultStep"), "no empty <value/> → no defaultStep");

        // All four output sinks present
        long outputCount = def.steps.stream().filter(s -> "TextFileOutput".equals(s.type)).count();
        assertEquals(4, outputCount);
        assertEquals("/path/to/csv/output_high_priority_pending.csv",
                def.steps.stream().filter(s -> "High Priority Pending Output".equals(s.id))
                        .findFirst().orElseThrow().params.get("filePath"));
    }

    // -------------------------------------------------------------------------
    // Blocking steps: TextFileInput → SortRows → GroupBy → FilterRows → output
    // -------------------------------------------------------------------------

    @Test
    void blockingSteps_sortGroupFilter_parsedCorrectly() throws Exception {
        // KTR: Read CSV (TextFileInput) → Sort Rows (SortRows) → Group By (GroupBy)
        //      → Filter High Value Customers (FilterRows) → Write CSV + Discard Rows
        //
        // Key exercises:
        //  - TextFileInput with <file><name>, flat <separator>/<header> on step
        //  - SortRows with <sortfields>/<field> (not <sort_fields>)
        //  - GroupBy with <aggregates>/<aggregate>/<name>/<subject>/<type> (new format)
        //  - FilterRows with <leftvalue><name> and <rightvalue><value> wrapping
        //  - Column index resolution across blocking step boundaries
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <name>read_sort_group_filter_write_csv</name>
              <step>
                <name>Read CSV</name>
                <type>TextFileInput</type>
                <file>
                  <name>C:/test/input/input.csv</name>
                </file>
                <separator>,</separator>
                <header>Y</header>
                <fields>
                  <field><name>customer_id</name><type>Integer</type></field>
                  <field><name>tx_date</name><type>Date</type></field>
                  <field><name>amount</name><type>Number</type></field>
                </fields>
              </step>
              <step>
                <name>Sort Rows</name>
                <type>SortRows</type>
                <sortfields>
                  <field><name>customer_id</name><ascending>Y</ascending></field>
                  <field><name>tx_date</name><ascending>Y</ascending></field>
                </sortfields>
              </step>
              <step>
                <name>Group By</name>
                <type>GroupBy</type>
                <group>
                  <field><name>customer_id</name></field>
                </group>
                <aggregates>
                  <aggregate>
                    <name>total_amount</name>
                    <subject>amount</subject>
                    <type>SUM</type>
                  </aggregate>
                </aggregates>
              </step>
              <step>
                <name>Filter High Value Customers</name>
                <type>FilterRows</type>
                <send_true_to>Write CSV</send_true_to>
                <send_false_to>Discard Rows</send_false_to>
                <condition>
                  <leftvalue><name>total_amount</name></leftvalue>
                  <function>GT</function>
                  <rightvalue><value>50000</value></rightvalue>
                </condition>
              </step>
              <step>
                <name>Write CSV</name>
                <type>TextFileOutput</type>
                <file><name>C:/test/output/output.csv</name><header>Y</header></file>
              </step>
              <step>
                <name>Discard Rows</name>
                <type>Dummy</type>
              </step>
              <hop><from>Read CSV</from><to>Sort Rows</to><enabled>Y</enabled></hop>
              <hop><from>Sort Rows</from><to>Group By</to><enabled>Y</enabled></hop>
              <hop><from>Group By</from><to>Filter High Value Customers</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Value Customers</from><to>Write CSV</to><enabled>Y</enabled></hop>
              <hop><from>Filter High Value Customers</from><to>Discard Rows</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("read_sort_group_filter_write_csv", def.name);
        assertEquals(6, def.steps.size());
        assertEquals(5, def.hops.size());

        // ── TextFileInput ────────────────────────────────────────────────────
        StepDefinition readCsv = def.steps.get(0);
        assertEquals("TextFileInput", readCsv.type);
        assertEquals("C:/test/input/input.csv", readCsv.params.get("filePath"));
        assertEquals("true", readCsv.params.get("hasHeader"));

        // ── SortRows: <sortfields>/<field> → columns resolved to indices ─────
        // Schema: [customer_id=0, tx_date=1, amount=2]
        StepDefinition sort = def.steps.get(1);
        assertEquals("SortRows", sort.type);
        assertEquals("0,1", sort.params.get("columns"),
                "customer_id→0, tx_date→1 from TextFileInput schema");

        // ── GroupBy: <aggregates>/<aggregate> new format ─────────────────────
        StepDefinition groupBy = def.steps.get(2);
        assertEquals("GroupBy", groupBy.type);
        assertEquals("0",           groupBy.params.get("groupColumns"),
                "customer_id is index 0");
        assertEquals("2",           groupBy.params.get("aggColumns"),
                "amount is index 2 — subject column resolved against upstream schema");
        assertEquals("SUM",         groupBy.params.get("aggFunctions"));
        assertEquals("total_amount", groupBy.params.get("aggNames"),
                "output column name from <aggregate>/<name>");

        // ── FilterRows: column resolved against GroupBy output schema ─────────
        // GroupBy output schema: [customer_id=0, total_amount=1]
        StepDefinition filter = def.steps.get(3);
        assertEquals("FilterRows", filter.type);
        assertEquals("1",        filter.params.get("column"),
                "total_amount is index 1 in GroupBy output [customer_id, total_amount]");
        assertEquals("GT",       filter.params.get("operator"));
        assertEquals("50000",    filter.params.get("value"));
        assertEquals("Write CSV",    filter.params.get("trueStep"));
        assertEquals("Discard Rows", filter.params.get("falseStep"));

        // ── Output and Dummy ─────────────────────────────────────────────────
        StepDefinition writeOut = def.steps.get(4);
        assertEquals("TextFileOutput", writeOut.type);
        assertEquals("C:/test/output/output.csv", writeOut.params.get("filePath"));

        StepDefinition discard = def.steps.get(5);
        assertEquals("Dummy", discard.type);
    }

    @Test
    void switchCase_withDefaultStep_parsedCorrectly() throws Exception {
        // Default case: empty <value/> → maps to defaultStep param
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>switch_default</name></info>
              <step>
                <name>src</name><type>CsvInput</type>
                <filename>/data/in.csv</filename>
                <fields>
                  <field><name>category</name></field>
                </fields>
              </step>
              <step>
                <name>sw</name><type>SwitchCase</type>
                <field_name>category</field_name>
                <case><value>A</value><target>Out A</target></case>
                <case><value/><target>Out Default</target></case>
              </step>
              <step><name>Out A</name><type>Dummy</type></step>
              <step><name>Out Default</name><type>Dummy</type></step>
              <order>
                <hop><from>src</from><to>sw</to><enabled>Y</enabled></hop>
                <hop><from>sw</from><to>Out A</to><enabled>Y</enabled></hop>
                <hop><from>sw</from><to>Out Default</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        StepDefinition sw = def.steps.stream().filter(s -> "sw".equals(s.id)).findFirst().orElseThrow();
        assertEquals("SwitchCase",  sw.type);
        assertEquals("0",           sw.params.get("column"), "category is first field → index 0");
        assertEquals("Out A",       sw.params.get("case.A"));
        assertEquals("Out Default", sw.params.get("defaultStep"));
    }

    // -------------------------------------------------------------------------
    // Mapping (Sub-transformation) — KTR calling another KTR
    // -------------------------------------------------------------------------

    @Test
    void mapping_flatFilename_parsedCorrectly() throws Exception {
        // Format A: <filename> directly on the step (Pentaho 7.x)
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>parent_ktr</name></info>
              <step>
                <name>Read Input</name><type>CsvInput</type>
                <filename>/data/input.csv</filename>
                <fields><field><name>id</name></field><field><name>value</name></field></fields>
              </step>
              <step>
                <name>Enrich Data</name>
                <type>Mapping (Sub-transformation)</type>
                <filename>transformations/enrich.ktr</filename>
              </step>
              <step>
                <name>Write Output</name><type>TextFileOutput</type>
                <file><name>/data/output.csv</name></file>
              </step>
              <order>
                <hop><from>Read Input</from><to>Enrich Data</to><enabled>Y</enabled></hop>
                <hop><from>Enrich Data</from><to>Write Output</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("parent_ktr", def.name);
        assertEquals(3, def.steps.size());
        assertEquals(2, def.hops.size());

        StepDefinition mapping = def.steps.stream()
                .filter(s -> "Enrich Data".equals(s.id)).findFirst().orElseThrow();
        assertEquals("Mapping", mapping.type);
        assertEquals("transformations/enrich.yaml", mapping.params.get("transformationPath"),
                ".ktr extension should be replaced with .yaml");
    }

    @Test
    void mapping_specificationMethodFilename_parsedCorrectly() throws Exception {
        // Format B: <specification_method>filename</specification_method> + <filename> (Pentaho 8.x/9.x)
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>parent_v9</name></info>
              <step>
                <name>src</name><type>CsvInput</type>
                <filename>/data/in.csv</filename>
                <fields><field><name>key</name></field></fields>
              </step>
              <step>
                <name>Sub Transform</name>
                <type>Mapping (Sub-transformation)</type>
                <specification_method>filename</specification_method>
                <filename>/opt/pentaho/transformations/lookup.ktr</filename>
              </step>
              <order>
                <hop><from>src</from><to>Sub Transform</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        StepDefinition mapping = def.steps.stream()
                .filter(s -> "Sub Transform".equals(s.id)).findFirst().orElseThrow();
        assertEquals("Mapping", mapping.type);
        assertEquals("/opt/pentaho/transformations/lookup.yaml",
                mapping.params.get("transformationPath"));
    }

    @Test
    void mapping_repositoryReference_parsedCorrectly() throws Exception {
        // Format C: <specification_method>rep_by_name</specification_method> + <directory> + <trans_name>
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>parent_repo</name></info>
              <step>
                <name>src</name><type>CsvInput</type>
                <filename>/data/in.csv</filename>
                <fields><field><name>key</name></field></fields>
              </step>
              <step>
                <name>Repo Sub</name>
                <type>Mapping (Sub-transformation)</type>
                <specification_method>rep_by_name</specification_method>
                <trans_name>customer_enrich</trans_name>
                <directory>/transformations/customer</directory>
              </step>
              <order>
                <hop><from>src</from><to>Repo Sub</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        StepDefinition mapping = def.steps.stream()
                .filter(s -> "Repo Sub".equals(s.id)).findFirst().orElseThrow();
        assertEquals("Mapping", mapping.type);
        assertEquals("/transformations/customer/customer_enrich.yaml",
                mapping.params.get("transformationPath"),
                "repository reference: directory/trans_name.yaml");
    }

    @Test
    void mapping_childKtrWithMappingInput_parsedCorrectly() throws Exception {
        // A CHILD KTR contains MappingInput → [processing] → MappingOutput.
        // The converter must parse MappingInput/MappingOutput as recognised step types (not Default).
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>child_enrichment</name></info>
              <step>
                <name>Input Port</name>
                <type>MappingInput</type>
              </step>
              <step>
                <name>Sort by Key</name>
                <type>SortRows</type>
                <sort_fields>
                  <field><name>key</name><ascending>Y</ascending></field>
                </sort_fields>
              </step>
              <step>
                <name>Output Port</name>
                <type>MappingOutput</type>
              </step>
              <order>
                <hop><from>Input Port</from><to>Sort by Key</to><enabled>Y</enabled></hop>
                <hop><from>Sort by Key</from><to>Output Port</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("child_enrichment", def.name);
        assertEquals(3, def.steps.size());

        assertEquals("MappingInput",  def.steps.get(0).type);
        assertEquals("SortRows",      def.steps.get(1).type);
        assertEquals("MappingOutput", def.steps.get(2).type);
    }

    // -------------------------------------------------------------------------
    // Transform3: AppendStreams fan-in (two CsvInputs → Append → TextFileOutput)
    // -------------------------------------------------------------------------

    @Test
    void transform3_appendStreamsFanIn_parsedCorrectly() throws Exception {
        // Matches the Transform3.ktr: two CsvInput steps feed AppendStreams, then TextFileOutput.
        // Exercises: <AppendStreams> → "Append" type mapping, fan-in hop topology.
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation xmlns="http://www.pentaho.com/kettle/transformation/">
              <info>
                <name>Transform3</name>
                <description>Merges output1.csv and output2.csv</description>
              </info>
              <step>
                <name>CSV Input 1</name>
                <type>CsvInput</type>
                <fields>
                  <field><name>id</name><type>Integer</type></field>
                  <field><name>value</name><type>String</type></field>
                </fields>
                <file><name>/path/to/output1.csv</name></file>
                <content><separator>,</separator><header>Y</header></content>
              </step>
              <step>
                <name>CSV Input 2</name>
                <type>CsvInput</type>
                <fields>
                  <field><name>code</name><type>String</type></field>
                  <field><name>description</name><type>String</type></field>
                </fields>
                <file><name>/path/to/output2.csv</name></file>
                <content><separator>,</separator><header>Y</header></content>
              </step>
              <step>
                <name>Append Streams</name>
                <type>AppendStreams</type>
              </step>
              <step>
                <name>Text File Output</name>
                <type>TextFileOutput</type>
                <file>
                  <name>/path/to/final_output.csv</name>
                  <separator>,</separator>
                  <header>Y</header>
                </file>
              </step>
              <hop><from>CSV Input 1</from><to>Append Streams</to><enabled>Y</enabled></hop>
              <hop><from>CSV Input 2</from><to>Append Streams</to><enabled>Y</enabled></hop>
              <hop><from>Append Streams</from><to>Text File Output</to><enabled>Y</enabled></hop>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertEquals("Transform3", def.name);
        assertEquals(4, def.steps.size());
        assertEquals(3, def.hops.size());

        // CSV Input 1
        StepDefinition csv1 = def.steps.get(0);
        assertEquals("CsvInput", csv1.type);
        assertEquals("/path/to/output1.csv", csv1.params.get("filePath"));

        // CSV Input 2
        StepDefinition csv2 = def.steps.get(1);
        assertEquals("CsvInput", csv2.type);
        assertEquals("/path/to/output2.csv", csv2.params.get("filePath"));

        // AppendStreams → normalised to "Append"
        StepDefinition append = def.steps.get(2);
        assertEquals("Append", append.type);

        // TextFileOutput
        StepDefinition out = def.steps.get(3);
        assertEquals("TextFileOutput", out.type);
        assertEquals("/path/to/final_output.csv", out.params.get("filePath"));

        // Fan-in: two hops converge on Append Streams
        long inboundToAppend = def.hops.stream()
                .filter(h -> "Append Streams".equals(h.to)).count();
        assertEquals(2, inboundToAppend, "Both CsvInput hops should target Append Streams");
    }

    // -------------------------------------------------------------------------
    // KTR parameters — variable substitution defaults
    // -------------------------------------------------------------------------

    @Test
    void ktrParameters_parsedIntoDefinition() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>param_test</name></info>
              <parameters>
                <parameter>
                  <name>INPUT_CSV</name>
                  <default_value>C:\\pentaho-samples\\input.csv</default_value>
                  <description/>
                </parameter>
                <parameter>
                  <name>OUTPUT_CSV</name>
                  <default_value>/data/output.csv</default_value>
                  <description/>
                </parameter>
              </parameters>
              <step>
                <name>read</name>
                <type>CSVInput</type>
                <filename>${INPUT_CSV}</filename>
                <header>Y</header>
              </step>
              <order/>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        assertNotNull(def.parameters, "parameters map must not be null");
        assertEquals(2, def.parameters.size());
        assertEquals("C:\\pentaho-samples\\input.csv", def.parameters.get("INPUT_CSV"));
        assertEquals("/data/output.csv",               def.parameters.get("OUTPUT_CSV"));

        // The step param carries the literal ${INPUT_CSV} token — resolution happens in executor
        StepDefinition read = def.steps.get(0);
        assertEquals("${INPUT_CSV}", read.params.get("filePath"));
    }

    // -------------------------------------------------------------------------
    // FilterRows — <compare><condition> nested format
    // -------------------------------------------------------------------------

    @Test
    void filterRows_compareNestedCondition_parsedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>filter_test</name></info>
              <step>
                <name>source</name>
                <type>CSVInput</type>
                <filename>/in.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>id</name></field>
                  <field><name>status</name></field>
                  <field><name>amount</name></field>
                </fields>
              </step>
              <step>
                <name>filter_active</name>
                <type>FilterRows</type>
                <send_true_to>active_out</send_true_to>
                <send_false_to>inactive_out</send_false_to>
                <compare>
                  <condition>
                    <leftvalue>status</leftvalue>
                    <function>=</function>
                    <rightvalue>ACTIVE</rightvalue>
                  </condition>
                </compare>
              </step>
              <step><name>active_out</name><type>Dummy (do nothing)</type></step>
              <step><name>inactive_out</name><type>Dummy (do nothing)</type></step>
              <order>
                <hop><from>source</from><to>filter_active</to><enabled>Y</enabled></hop>
                <hop><from>filter_active</from><to>active_out</to><enabled>Y</enabled></hop>
                <hop><from>filter_active</from><to>inactive_out</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        StepDefinition filter = def.steps.stream()
                .filter(s -> "FilterRows".equals(s.type))
                .findFirst().orElseThrow();

        // column name "status" should be resolved to index 1 (0=id, 1=status, 2=amount)
        assertEquals("1",       filter.params.get("column"),   "status column index should be 1");
        assertEquals("EQ",      filter.params.get("operator"), "= should normalise to EQ");
        assertEquals("ACTIVE",  filter.params.get("value"));
        assertEquals("active_out",   filter.params.get("trueStep"));
        assertEquals("inactive_out", filter.params.get("falseStep"));
    }

    // -------------------------------------------------------------------------
    // StreamLookup — mapper + KtrParser integration
    // -------------------------------------------------------------------------

    @Test
    void streamLookup_parsedCorrectly() throws Exception {
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>lookup_test</name></info>
              <step>
                <name>Main Stream</name>
                <type>CSVInput</type>
                <filename>/data/orders.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>order_id</name></field>
                  <field><name>customer_name</name></field>
                </fields>
              </step>
              <step>
                <name>Lookup Source</name>
                <type>CSVInput</type>
                <filename>/data/prices.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>item_id</name></field>
                  <field><name>unit_price</name></field>
                  <field><name>currency</name></field>
                </fields>
              </step>
              <step>
                <name>Enrich Orders</name>
                <type>StreamLookup</type>
                <from>Lookup Source</from>
                <lookup>
                  <key>
                    <name>order_id</name>
                    <field>item_id</field>
                  </key>
                  <value>
                    <name>unit_price</name>
                    <rename>price</rename>
                  </value>
                  <value>
                    <name>currency</name>
                    <rename/>
                  </value>
                </lookup>
              </step>
              <step><name>Output</name><type>TextFileOutput</type><file><name>/out.csv</name></file></step>
              <order>
                <hop><from>Main Stream</from><to>Enrich Orders</to><enabled>Y</enabled></hop>
                <hop><from>Lookup Source</from><to>Enrich Orders</to><enabled>Y</enabled></hop>
                <hop><from>Enrich Orders</from><to>Output</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        StepDefinition lookup = def.steps.stream()
                .filter(s -> "StreamLookup".equals(s.type))
                .findFirst().orElseThrow();

        // lookupStep must name the lookup source
        assertEquals("Lookup Source", lookup.params.get("lookupStep"));

        // Raw field names preserved for documentation; resolved index params are the important ones
        assertEquals("order_id", lookup.params.get("keyStream"));
        assertEquals("item_id",  lookup.params.get("keyLookup"));

        // Resolved indices: "Main Stream" schema=[order_id=0,customer_name=1]
        assertEquals("0", lookup.params.get("keyStreamCols"), "order_id is col 0 in main stream");
        // Resolved indices: "Lookup Source" schema=[item_id=0,unit_price=1,currency=2]
        assertEquals("0", lookup.params.get("keyLookupCols"),  "item_id is col 0 in lookup");
        assertEquals("1,2", lookup.params.get("valueFieldCols"), "unit_price=1, currency=2");

        // lookupInputIndex: "Lookup Source" is second in allUpstreams (main added first via hop order)
        assertEquals("1", lookup.params.get("lookupInputIndex"));

        // Renamed output fields
        assertEquals("unit_price,currency", lookup.params.get("valueFields"));
        assertEquals("price,currency",      lookup.params.get("valueRenames"));
    }

    // -------------------------------------------------------------------------
    // StreamLookup — lookup hop absent from <order> (real-world Pentaho export style)
    // -------------------------------------------------------------------------

    @Test
    void streamLookup_lookupHopMissingFromOrder_syntheticHopInjected() throws Exception {
        // In many real Pentaho KTR exports the lookup-stream hop does NOT appear in <order>.
        // Only the main stream hop is there; the lookup source is only named via <from>
        // inside the StreamLookup step XML. The parser must inject a synthetic hop so the
        // executor receives two inputs instead of one (and avoids IOOBE).
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>lookup_no_order_hop</name></info>
              <step>
                <name>Main CSV</name>
                <type>CSVInput</type>
                <filename>/data/main.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>id</name></field>
                  <field><name>name</name></field>
                </fields>
              </step>
              <step>
                <name>Lookup CSV</name>
                <type>CSVInput</type>
                <filename>/data/lookup.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>ref_id</name></field>
                  <field><name>category</name></field>
                </fields>
              </step>
              <step>
                <name>Enrich</name>
                <type>StreamLookup</type>
                <from>Lookup CSV</from>
                <lookup>
                  <key><name>id</name><field>ref_id</field></key>
                  <value><name>category</name><rename>cat</rename></value>
                </lookup>
              </step>
              <step><name>Out</name><type>TextFileOutput</type><file><name>/out.csv</name></file></step>
              <order>
                <!-- Only the main-stream hop is listed; lookup hop deliberately absent -->
                <hop><from>Main CSV</from><to>Enrich</to><enabled>Y</enabled></hop>
                <hop><from>Enrich</from><to>Out</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        // The parser must have injected a synthetic hop: Lookup CSV → Enrich
        long hopsToEnrich = def.hops.stream()
                .filter(h -> "Enrich".equals(h.to) && h.enabled)
                .count();
        assertEquals(2, hopsToEnrich, "Both main-stream and lookup-stream hops must exist");

        boolean syntheticPresent = def.hops.stream()
                .anyMatch(h -> "Lookup CSV".equals(h.from) && "Enrich".equals(h.to));
        assertTrue(syntheticPresent, "Synthetic lookup hop must be injected");

        // lookupInputIndex must still be correct (lookup is the synthetic / second upstream)
        StepDefinition enrich = def.steps.stream()
                .filter(s -> "StreamLookup".equals(s.type))
                .findFirst().orElseThrow();
        assertEquals("1", enrich.params.get("lookupInputIndex"),
                "Lookup stream must be assigned to input index 1");

        // Column indices must be resolved despite hop being absent from <order>
        assertEquals("0", enrich.params.get("keyStreamCols"),  "id=col0 in main");
        assertEquals("0", enrich.params.get("keyLookupCols"),  "ref_id=col0 in lookup");
        assertEquals("1", enrich.params.get("valueFieldCols"), "category=col1 in lookup");
    }

    // -------------------------------------------------------------------------
    // StreamLookup fallback schema when lookup CsvInput has no <fields> in KTR
    // -------------------------------------------------------------------------

    @Test
    void streamLookup_formatB_keystream_valuestream_parsedCorrectly() throws Exception {
        // Real-world KTR format: flat <keystream>, <keylookup>, <valuestream> siblings
        // (as opposed to the <lookup>/<key>/<value> container format).
        // Lookup source has <fields> declared, so schema IS available for index resolution.
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>filter_lookup_sort</name></info>
              <step>
                <name>Read Input CSV</name>
                <type>CsvInput</type>
                <filename>${INPUT_CSV}</filename>
                <header>Y</header>
                <fields>
                  <field><name>id</name></field>
                  <field><name>first_name</name></field>
                  <field><name>last_name</name></field>
                  <field><name>status</name></field>
                </fields>
              </step>
              <step>
                <name>Filter ACTIVE</name>
                <type>FilterRows</type>
                <send_true_to>Lookup Attributes</send_true_to>
                <send_false_to></send_false_to>
                <compare>
                  <condition>
                    <negated>N</negated>
                    <leftvalue>status</leftvalue>
                    <function>=</function>
                    <rightvalue>ACTIVE</rightvalue>
                  </condition>
                </compare>
              </step>
              <step>
                <name>Lookup Attributes</name>
                <type>StreamLookup</type>
                <keystream>id</keystream>
                <keylookup>id</keylookup>
                <lookupsteps>
                  <lookupstep><name>Read Lookup CSV</name></lookupstep>
                </lookupsteps>
                <valuestream>
                  <valuename>country</valuename>
                  <value>country</value>
                  <default></default>
                  <type>String</type>
                </valuestream>
                <valuestream>
                  <valuename>segment</valuename>
                  <value>segment</value>
                  <default></default>
                  <type>String</type>
                </valuestream>
              </step>
              <step>
                <name>Read Lookup CSV</name>
                <type>CsvInput</type>
                <filename>${LOOKUP_CSV}</filename>
                <header>Y</header>
                <fields>
                  <field><name>id</name></field>
                  <field><name>country</name></field>
                  <field><name>segment</name></field>
                </fields>
              </step>
              <step>
                <name>Sort Rows</name>
                <type>SortRows</type>
                <fields>
                  <field><name>last_name</name><ascending>Y</ascending></field>
                  <field><name>first_name</name><ascending>Y</ascending></field>
                </fields>
              </step>
              <step>
                <name>Write Output CSV</name>
                <type>TextFileOutput</type>
                <filename>${OUTPUT_CSV}</filename>
                <separator>,</separator>
                <header>Y</header>
                <file>
                  <servlet_output>N</servlet_output>
                </file>
                <fields>
                  <field><name>id</name></field>
                  <field><name>first_name</name></field>
                  <field><name>last_name</name></field>
                  <field><name>status</name></field>
                  <field><name>country</name></field>
                  <field><name>segment</name></field>
                </fields>
              </step>
              <order>
                <hop><from>Read Input CSV</from><to>Filter ACTIVE</to><enabled>Y</enabled></hop>
                <hop><from>Filter ACTIVE</from><to>Lookup Attributes</to><enabled>Y</enabled></hop>
                <hop><from>Lookup Attributes</from><to>Sort Rows</to><enabled>Y</enabled></hop>
                <hop><from>Sort Rows</from><to>Write Output CSV</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);

        // StreamLookup — Format B params
        StepDefinition lookup = def.steps.stream()
                .filter(s -> "Lookup Attributes".equals(s.id)).findFirst().orElseThrow();

        assertEquals("Read Lookup CSV", lookup.params.get("lookupStep"));
        // keyStreamCols: "id" is at index 0 in [id,first_name,last_name,status] main schema
        assertEquals("0", lookup.params.get("keyStreamCols"), "id=col0 in main stream");
        // keyLookupCols: "id" is at index 0 in [id,country,segment] lookup schema
        assertEquals("0", lookup.params.get("keyLookupCols"), "id=col0 in lookup stream");
        // valueFieldCols: country=1, segment=2 in [id,country,segment] lookup schema
        assertEquals("1,2", lookup.params.get("valueFieldCols"),
                "country=col1, segment=col2 in lookup stream");

        // TextFileOutput — outputCols from resolved <fields>
        // StreamLookup output schema = [id,first_name,last_name,status,country,segment]
        StepDefinition out = def.steps.stream()
                .filter(s -> "Write Output CSV".equals(s.id)).findFirst().orElseThrow();

        assertEquals("${OUTPUT_CSV}", out.params.get("filePath"));
        assertEquals("true", out.params.get("writeHeader"));
        assertEquals("0,1,2,3,4,5", out.params.get("outputCols"),
                "All 6 fields resolved in correct order");
        assertNull(out.params.get("outputFields"), "outputFields consumed, outputCols set");

        // Synthetic hop injected for lookup stream
        long syntheticHops = def.hops.stream()
                .filter(h -> "Read Lookup CSV".equals(h.from) && "Lookup Attributes".equals(h.to))
                .count();
        assertEquals(1, syntheticHops, "Synthetic hop for lookup stream must be injected");
    }

    @Test
    void streamLookup_noLookupFieldSchema_fallsBackToSyntheticSchema() throws Exception {
        // The lookup CsvInput has NO <fields>/<field> declarations — common when the CSV
        // schema is auto-detected in Pentaho.  The StreamLookup mapper can still infer
        // column positions from its own <key>/<field> and <value>/<name> elements:
        //   synthetic schema = [key_lookup_fields..., value_fields...]
        //   → keyLookupCols = "0", valueFieldCols = "1,2"
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>lookup_no_schema</name></info>
              <step>
                <name>Main</name>
                <type>CSVInput</type>
                <filename>/data/main.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>order_key</name></field>
                  <field><name>amount</name></field>
                </fields>
              </step>
              <!-- Lookup source has NO <fields> declarations -->
              <step>
                <name>LookupSrc</name>
                <type>CSVInput</type>
                <filename>/data/lookup.csv</filename>
                <header>Y</header>
              </step>
              <step>
                <name>Enrich</name>
                <type>StreamLookup</type>
                <from>LookupSrc</from>
                <lookup>
                  <key><name>order_key</name><field>id</field></key>
                  <value><name>country</name><rename>country</rename></value>
                  <value><name>segment</name><rename>segment</rename></value>
                </lookup>
              </step>
              <order>
                <hop><from>Main</from><to>Enrich</to><enabled>Y</enabled></hop>
                <hop><from>LookupSrc</from><to>Enrich</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition enrich = def.steps.stream()
                .filter(s -> "Enrich".equals(s.id)).findFirst().orElseThrow();

        // keyStreamCols: order_key is at index 0 in Main schema
        assertEquals("0", enrich.params.get("keyStreamCols"));
        // keyLookupCols: synthetic schema = [id, country, segment] → id at index 0
        assertEquals("0", enrich.params.get("keyLookupCols"),
                "Fallback schema: key field 'id' must be at index 0");
        // valueFieldCols: country at index 1, segment at index 2 in synthetic schema
        assertEquals("1,2", enrich.params.get("valueFieldCols"),
                "Fallback schema: country=1, segment=2");
    }

    // -------------------------------------------------------------------------
    // TextFileOutput — outputFields resolved to outputCols
    // -------------------------------------------------------------------------

    @Test
    void textFileOutput_outputFields_resolvedToColumnIndices() throws Exception {
        // The TextFileOutput <fields> section selects a subset of columns and gives the output
        // its expected schema. The parser must resolve those names to 0-based indices so the
        // step can write only those columns in the correct order.
        String ktr = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>select_output</name></info>
              <step>
                <name>Src</name>
                <type>CSVInput</type>
                <filename>/data/in.csv</filename>
                <header>Y</header>
                <fields>
                  <field><name>id</name></field>
                  <field><name>name</name></field>
                  <field><name>country</name></field>
                  <field><name>segment</name></field>
                </fields>
              </step>
              <step>
                <name>Out</name>
                <type>TextFileOutput</type>
                <file><name>/data/out.csv</name></file>
                <fields>
                  <field><name>country</name></field>
                  <field><name>segment</name></field>
                </fields>
              </step>
              <order>
                <hop><from>Src</from><to>Out</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

        TransformationDefinition def = parse(ktr);
        StepDefinition out = def.steps.stream()
                .filter(s -> "Out".equals(s.id)).findFirst().orElseThrow();

        // outputFields should be consumed and replaced with outputCols
        assertNull(out.params.get("outputFields"), "outputFields must be replaced by outputCols");
        assertEquals("2,3", out.params.get("outputCols"),
                "country=col2, segment=col3 in upstream [id,name,country,segment]");
    }

    // =========================================================================
    // CSV_Oracle_Merge pattern: <delimiter>, SortRows <ascending>, named connection,
    // MergeJoin key with "StepName.field" prefix
    // =========================================================================

    /** CsvInputMapper must accept <delimiter> as a synonym for <separator>. */
    @Test
    void csvInput_delimiter_treatedAsSeparator() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <step>
                    <name>ReadCsv</name><type>CsvInput</type>
                    <filename>/data/in.csv</filename>
                    <delimiter>|</delimiter>
                    <header>Y</header>
                    <fields><field><name>a</name></field></fields>
                  </step>
                  <order/>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition step = def.steps.get(0);
        assertEquals("|", step.params.get("separator"),
                "<delimiter> must be mapped to the 'separator' param");
    }

    /** SortRowsMapper must emit an 'ascending' param reflecting <ascending>Y/N</ascending>. */
    @Test
    void sortRows_ascendingFlag_emitted() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <step>
                    <name>Src</name><type>CsvInput</type>
                    <filename>/data/in.csv</filename><header>Y</header>
                    <fields>
                      <field><name>col_a</name></field>
                      <field><name>col_b</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>Sort</name><type>SortRows</type>
                    <fields>
                      <field><name>col_a</name><ascending>Y</ascending></field>
                      <field><name>col_b</name><ascending>N</ascending></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>Src</from><to>Sort</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition sort = def.steps.stream()
                .filter(s -> "Sort".equals(s.id)).findFirst().orElseThrow();
        // col_a=0, col_b=1 in upstream schema
        assertEquals("0,1", sort.params.get("columns"), "columns resolved to indices");
        assertEquals("true,false", sort.params.get("ascending"),
                "ascending: Y→true, N→false");
    }

    /** SortRowsMapper also understands <sort_direction>ascending/descending</sort_direction>. */
    @Test
    void sortRows_sortDirection_alternateFormat() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <step>
                    <name>Src</name><type>CsvInput</type>
                    <filename>/data/in.csv</filename><header>Y</header>
                    <fields>
                      <field><name>price</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>Sort</name><type>SortRows</type>
                    <fields>
                      <field><name>price</name><sort_direction>descending</sort_direction></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>Src</from><to>Sort</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition sort = def.steps.stream()
                .filter(s -> "Sort".equals(s.id)).findFirst().orElseThrow();
        assertEquals("false", sort.params.get("ascending"), "<sort_direction>descending → false");
    }

    /**
     * KtrParser resolves a named connection referenced by a TableInput step.
     * Root-level {@code <connection>} elements with a {@code <name>} child supply the JDBC params.
     */
    @Test
    void tableInput_namedConnection_resolvedFromRoot() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <!-- Root-level named connection definition -->
                  <connection>
                    <name>ORACLE_CONN</name>
                    <server>oracle-host</server>
                    <port>1521</port>
                    <database>MYDB</database>
                    <type>ORACLE</type>
                    <username>scott</username>
                    <password>tiger</password>
                  </connection>
                  <step>
                    <name>ReadOracle</name>
                    <type>TableInput</type>
                    <!-- Name reference only — no inline connection block -->
                    <connection>ORACLE_CONN</connection>
                    <sql><![CDATA[SELECT CUST_ID, NAME, AGE FROM CUSTOMERS]]></sql>
                  </step>
                  <order/>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition step = def.steps.get(0);
        // JDBC params must be resolved from the named connection
        assertEquals("oracle",      step.params.get("dbType"),      "dbType");
        assertEquals("oracle-host", step.params.get("jdbcHost"),    "jdbcHost");
        assertEquals("1521",        step.params.get("jdbcPort"),    "jdbcPort");
        assertEquals("MYDB",        step.params.get("jdbcSid"),     "jdbcSid (Oracle uses SID)");
        assertEquals("scott",       step.params.get("jdbcUser"),    "jdbcUser");
        assertEquals("tiger",       step.params.get("jdbcPassword"),"jdbcPassword");
        // SQL preserved
        assertNotNull(step.params.get("query"), "query must be present");
        assertTrue(step.params.get("query").contains("CUST_ID"), "SQL content preserved");
    }

    /**
     * KtrParser extracts column names from a simple SELECT for TableInput schema propagation.
     * This allows downstream SortRows / MergeJoin to resolve field names to indices.
     */
    @Test
    void tableInput_sqlSelectColumns_usedForSchemaAndSortResolution() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <connection>
                    <name>DB</name><server>h</server><port>5432</port>
                    <database>d</database><type>POSTGRESQL</type>
                    <username>u</username><password>p</password>
                  </connection>
                  <step>
                    <name>ReadDB</name><type>TableInput</type>
                    <connection>DB</connection>
                    <sql>SELECT CUST_ID, NAME, AGE FROM CUSTOMERS</sql>
                  </step>
                  <step>
                    <name>SortDB</name><type>SortRows</type>
                    <fields>
                      <field><name>CUST_ID</name><ascending>Y</ascending></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>ReadDB</from><to>SortDB</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition sort = def.steps.stream()
                .filter(s -> "SortDB".equals(s.id)).findFirst().orElseThrow();
        // CUST_ID is at index 0 in [CUST_ID, NAME, AGE]
        assertEquals("0", sort.params.get("columns"),
                "CUST_ID resolved to index 0 via SQL schema");
    }

    /** parseSqlSelectColumns utility handles aliases and table-qualified names. */
    @Test
    void parseSqlSelectColumns_variousFormats() {
        // Plain names
        assertEquals(List.of("CUST_ID", "NAME", "AGE"),
                KtrParser.parseSqlSelectColumns("SELECT CUST_ID, NAME, AGE FROM CUSTOMERS"));
        // Table-qualified
        assertEquals(List.of("CUST_ID", "NAME"),
                KtrParser.parseSqlSelectColumns("SELECT t.CUST_ID, t.NAME FROM T t"));
        // Aliases
        assertEquals(List.of("ID", "FULLNAME"),
                KtrParser.parseSqlSelectColumns("SELECT t.CUST_ID AS ID, t.NAME AS FULLNAME FROM T t"));
        // SELECT * → empty (can't determine schema)
        assertTrue(KtrParser.parseSqlSelectColumns("SELECT * FROM T").isEmpty());
        // Aggregate function → empty
        assertTrue(KtrParser.parseSqlSelectColumns("SELECT COUNT(*) FROM T").isEmpty());
        // Null/blank → empty
        assertTrue(KtrParser.parseSqlSelectColumns(null).isEmpty());
        assertTrue(KtrParser.parseSqlSelectColumns("  ").isEmpty());
    }

    /**
     * MergeJoinMapper strips the "StepName." prefix from key values.
     * Older Pentaho exports write {@code <key>StepName.FieldName</key>}.
     */
    @Test
    void mergeJoin_stepNamePrefixStrippedFromKeys() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info><name>t</name></info>
                  <step>
                    <name>Read CSV</name><type>CsvInput</type>
                    <filename>/data/in.csv</filename><header>Y</header>
                    <fields>
                      <field><name>CUST_ID</name></field>
                      <field><name>CITY</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>Read Oracle</name><type>CsvInput</type>
                    <filename>/data/ora.csv</filename><header>Y</header>
                    <fields>
                      <field><name>CUST_ID</name></field>
                      <field><name>NAME</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>Sort CSV</name><type>SortRows</type>
                    <fields><field><name>CUST_ID</name><ascending>Y</ascending></field></fields>
                  </step>
                  <step>
                    <name>Sort Oracle</name><type>SortRows</type>
                    <fields><field><name>CUST_ID</name><ascending>Y</ascending></field></fields>
                  </step>
                  <step>
                    <name>Merge Join</name><type>MergeJoin</type>
                    <join_type>INNER</join_type>
                    <step1>Sort CSV</step1>
                    <step2>Sort Oracle</step2>
                    <!-- Older format: step-qualified key names -->
                    <keys_1><key>Read CSV.CUST_ID</key></keys_1>
                    <keys_2><key>Read Oracle.CUST_ID</key></keys_2>
                  </step>
                  <order>
                    <hop><from>Read CSV</from><to>Sort CSV</to><enabled>Y</enabled></hop>
                    <hop><from>Read Oracle</from><to>Sort Oracle</to><enabled>Y</enabled></hop>
                    <hop><from>Sort CSV</from><to>Merge Join</to><enabled>Y</enabled></hop>
                    <hop><from>Sort Oracle</from><to>Merge Join</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """;
        TransformationDefinition def = parse(xml);
        StepDefinition merge = def.steps.stream()
                .filter(s -> "Merge Join".equals(s.id)).findFirst().orElseThrow();

        // "Read CSV.CUST_ID" → stripped to "CUST_ID" → resolved to index 0 in [CUST_ID, CITY]
        assertEquals("0", merge.params.get("leftColumns"),
                "leftColumns: CUST_ID is index 0 in Sort CSV's schema");
        // "Read Oracle.CUST_ID" → stripped to "CUST_ID" → resolved to index 0 in [CUST_ID, NAME]
        assertEquals("0", merge.params.get("rightColumns"),
                "rightColumns: CUST_ID is index 0 in Sort Oracle's schema");
    }
}
