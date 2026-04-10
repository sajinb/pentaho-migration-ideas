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
}
