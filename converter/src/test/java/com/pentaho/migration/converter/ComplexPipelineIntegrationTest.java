package com.pentaho.migration.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.engine.JobExecutor;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.entry.JobEntryRegistry;
import com.pentaho.migration.model.JobDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.step.StepRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test for the complex 3-KTR / 1-KJB sales pipeline
 * defined in samples/complex-pipeline/.
 *
 * <p>Test strategy:
 * <ol>
 *   <li>Inline the XML from the sample files (so the test is self-contained and
 *       does not depend on the samples directory being present on CI).</li>
 *   <li>Convert each KTR XML → YAML using {@link KtrParser}.</li>
 *   <li>Convert the KJB XML → YAML using {@link KjbParser}.</li>
 *   <li>Write YAML files to a {@link TempDir}.</li>
 *   <li>Write sample CSV data files to the same {@link TempDir} under {@code input/} and
 *       create an {@code output/} subdirectory.</li>
 *   <li>Execute each KTR individually and the KJB end-to-end, asserting output content.</li>
 * </ol>
 */
class ComplexPipelineIntegrationTest {

    private final KtrParser      ktrParser = new KtrParser();
    private final KjbParser      kjbParser = new KjbParser();
    private final ObjectMapper   yaml      = new ObjectMapper(new YAMLFactory());

    @TempDir Path tmp;

    // =========================================================================
    // Sample CSV data (mirrors samples/complex-pipeline/input/)
    // =========================================================================

    private static final String ORDERS_CSV = """
            order_id,customer_id,product_id,quantity,unit_price,status
            1001,C001,P005,2,29.99,CONFIRMED
            1002,C002,P012,1,149.99,CANCELLED
            1003,C001,P005,3,29.99,CONFIRMED
            1004,C003,P008,1,79.99,CONFIRMED
            1005,C002,P012,2,149.99,CONFIRMED
            """;

    private static final String CUSTOMERS_CSV = """
            cust_id,first_name,last_name,country,segment
            C001,Alice,Smith,UK,PREMIUM
            C002,Bob,Jones,US,STANDARD
            C003,Carol,Brown,UK,PREMIUM
            """;

    private static final String PRODUCTS_CSV = """
            prod_id,product_name,category,price
            P005,Widget Pro,Electronics,29.99
            P008,Desk Lamp,HomeGoods,79.99
            P012,Laptop Stand,Electronics,149.99
            """;

    // =========================================================================
    // KTR / KJB XML (mirrors samples/complex-pipeline/)
    // =========================================================================

    /** KTR 1: FilterRows fan-out — CONFIRMED → confirmed_orders.csv, others → rejected_orders.csv */
    private String ktr1Xml(String basePath) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info>
                    <name>01_filter_orders</name>
                    <parameters><parameter><name>BASE_PATH</name><default_value></default_value></parameter></parameters>
                  </info>
                  <step>
                    <name>read_orders</name><type>CsvInput</type>
                    <filename>BASE_PATH_TOKEN/input/orders.csv</filename>
                    <header>Y</header><separator>,</separator>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>filter_status</name><type>FilterRows</type>
                    <send_true_to>confirmed_output</send_true_to>
                    <send_false_to>rejected_output</send_false_to>
                    <compare><fieldname>status</fieldname><value>CONFIRMED</value></compare>
                  </step>
                  <step>
                    <name>confirmed_output</name><type>TextFileOutput</type>
                    <file>
                      <name>BASE_PATH_TOKEN/output/confirmed_orders.csv</name>
                      <separator>,</separator><header>Y</header>
                    </file>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>rejected_output</name><type>TextFileOutput</type>
                    <file>
                      <name>BASE_PATH_TOKEN/output/rejected_orders.csv</name>
                      <separator>,</separator><header>Y</header>
                    </file>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>read_orders</from><to>filter_status</to><enabled>Y</enabled></hop>
                    <hop><from>filter_status</from><to>confirmed_output</to><enabled>Y</enabled></hop>
                    <hop><from>filter_status</from><to>rejected_output</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """.replace("BASE_PATH_TOKEN", basePath);
    }

    /** KTR 2: StreamLookup Format B — enrich with customer data, sort by last_name */
    private String ktr2Xml(String basePath) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info>
                    <name>02_enrich_customers</name>
                    <parameters><parameter><name>BASE_PATH</name><default_value></default_value></parameter></parameters>
                  </info>
                  <step>
                    <name>read_confirmed</name><type>CsvInput</type>
                    <filename>BASE_PATH_TOKEN/output/confirmed_orders.csv</filename>
                    <header>Y</header><separator>,</separator>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>read_customers</name><type>CsvInput</type>
                    <filename>BASE_PATH_TOKEN/input/customers.csv</filename>
                    <header>Y</header><separator>,</separator>
                    <fields>
                      <field><name>cust_id</name></field>
                      <field><name>first_name</name></field>
                      <field><name>last_name</name></field>
                      <field><name>country</name></field>
                      <field><name>segment</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>enrich_customers</name><type>StreamLookup</type>
                    <from>read_customers</from>
                    <keystream>customer_id</keystream>
                    <keylookup>cust_id</keylookup>
                    <valuestream><value>first_name</value><valuename>first_name</valuename></valuestream>
                    <valuestream><value>last_name</value><valuename>last_name</valuename></valuestream>
                    <valuestream><value>country</value><valuename>country</valuename></valuestream>
                  </step>
                  <step>
                    <name>sort_by_name</name><type>SortRows</type>
                    <fields><field><name>last_name</name><sort_direction>ascending</sort_direction></field></fields>
                  </step>
                  <step>
                    <name>write_enriched</name><type>TextFileOutput</type>
                    <file>
                      <name>BASE_PATH_TOKEN/output/enriched_orders.csv</name>
                      <separator>,</separator><header>Y</header>
                    </file>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                      <field><name>first_name</name></field>
                      <field><name>last_name</name></field>
                      <field><name>country</name></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>read_confirmed</from><to>enrich_customers</to><enabled>Y</enabled></hop>
                    <hop><from>read_customers</from><to>enrich_customers</to><enabled>Y</enabled></hop>
                    <hop><from>enrich_customers</from><to>sort_by_name</to><enabled>Y</enabled></hop>
                    <hop><from>sort_by_name</from><to>write_enriched</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """.replace("BASE_PATH_TOKEN", basePath);
    }

    /** KTR 3: StreamLookup Format A — enrich with product data, sort by category */
    private String ktr3Xml(String basePath) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <transformation>
                  <info>
                    <name>03_enrich_products</name>
                    <parameters><parameter><name>BASE_PATH</name><default_value></default_value></parameter></parameters>
                  </info>
                  <step>
                    <name>read_enriched</name><type>CsvInput</type>
                    <filename>BASE_PATH_TOKEN/output/enriched_orders.csv</filename>
                    <header>Y</header><separator>,</separator>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>customer_id</name></field>
                      <field><name>product_id</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                      <field><name>status</name></field>
                      <field><name>first_name</name></field>
                      <field><name>last_name</name></field>
                      <field><name>country</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>read_products</name><type>CsvInput</type>
                    <filename>BASE_PATH_TOKEN/input/products.csv</filename>
                    <header>Y</header><separator>,</separator>
                    <fields>
                      <field><name>prod_id</name></field>
                      <field><name>product_name</name></field>
                      <field><name>category</name></field>
                      <field><name>price</name></field>
                    </fields>
                  </step>
                  <step>
                    <name>enrich_products</name><type>StreamLookup</type>
                    <from>read_products</from>
                    <lookup>
                      <key>
                        <name>product_id</name>
                        <field>prod_id</field>
                      </key>
                      <value>
                        <name>product_name</name>
                        <rename>product_name</rename>
                      </value>
                      <value>
                        <name>category</name>
                        <rename>category</rename>
                      </value>
                    </lookup>
                  </step>
                  <step>
                    <name>sort_by_category</name><type>SortRows</type>
                    <fields><field><name>category</name><sort_direction>ascending</sort_direction></field></fields>
                  </step>
                  <step>
                    <name>write_final</name><type>TextFileOutput</type>
                    <file>
                      <name>BASE_PATH_TOKEN/output/final_report.csv</name>
                      <separator>,</separator><header>Y</header>
                    </file>
                    <fields>
                      <field><name>order_id</name></field>
                      <field><name>first_name</name></field>
                      <field><name>last_name</name></field>
                      <field><name>country</name></field>
                      <field><name>product_name</name></field>
                      <field><name>category</name></field>
                      <field><name>quantity</name></field>
                      <field><name>unit_price</name></field>
                    </fields>
                  </step>
                  <order>
                    <hop><from>read_enriched</from><to>enrich_products</to><enabled>Y</enabled></hop>
                    <hop><from>read_products</from><to>enrich_products</to><enabled>Y</enabled></hop>
                    <hop><from>enrich_products</from><to>sort_by_category</to><enabled>Y</enabled></hop>
                    <hop><from>sort_by_category</from><to>write_final</to><enabled>Y</enabled></hop>
                  </order>
                </transformation>
                """.replace("BASE_PATH_TOKEN", basePath);
    }

    private String kjbXml(String basePath) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <job>
                  <name>sales_pipeline</name>
                  <parameters>
                    <parameter><name>BASE_PATH</name><default_value>BASE_PATH_TOKEN</default_value></parameter>
                  </parameters>
                  <entries>
                    <entry><name>START</name><type>SPECIAL</type><start>Y</start></entry>
                    <entry><name>filter_orders</name><type>TRANS</type>
                      <filename>BASE_PATH_TOKEN/ktr/01_filter_orders.ktr</filename></entry>
                    <entry><name>enrich_customers</name><type>TRANS</type>
                      <filename>BASE_PATH_TOKEN/ktr/02_enrich_customers.ktr</filename></entry>
                    <entry><name>enrich_products</name><type>TRANS</type>
                      <filename>BASE_PATH_TOKEN/ktr/03_enrich_products.ktr</filename></entry>
                    <entry><name>SUCCESS</name><type>SPECIAL</type><success>Y</success></entry>
                  </entries>
                  <hops>
                    <hop><from>START</from><to>filter_orders</to>
                         <enabled>Y</enabled><unconditional>Y</unconditional></hop>
                    <hop><from>filter_orders</from><to>enrich_customers</to>
                         <enabled>Y</enabled><evaluation>true</evaluation><unconditional>N</unconditional></hop>
                    <hop><from>enrich_customers</from><to>enrich_products</to>
                         <enabled>Y</enabled><evaluation>true</evaluation><unconditional>N</unconditional></hop>
                    <hop><from>enrich_products</from><to>SUCCESS</to>
                         <enabled>Y</enabled><evaluation>true</evaluation><unconditional>N</unconditional></hop>
                  </hops>
                </job>
                """.replace("BASE_PATH_TOKEN", basePath);
    }

    // =========================================================================
    // Helper utilities
    // =========================================================================

    private void writeInputFiles() throws IOException {
        Path inputDir  = tmp.resolve("input");
        Path outputDir = tmp.resolve("output");
        Files.createDirectories(inputDir);
        Files.createDirectories(outputDir);
        Files.writeString(inputDir.resolve("orders.csv"),    ORDERS_CSV);
        Files.writeString(inputDir.resolve("customers.csv"), CUSTOMERS_CSV);
        Files.writeString(inputDir.resolve("products.csv"),  PRODUCTS_CSV);
    }

    private TransformationDefinition parseAndWriteKtr(String xml, String yamlFileName)
            throws Exception {
        TransformationDefinition def = ktrParser.parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        String yamlContent = yaml.writeValueAsString(def);
        Files.writeString(tmp.resolve(yamlFileName), yamlContent);
        return def;
    }

    private TransformationExecutor executor() {
        return new TransformationExecutor(StepRegistry.withDefaults()).withTempDir(tmp);
    }

    private List<String> readOutputLines(String filename) throws IOException {
        return Files.readAllLines(tmp.resolve("output").resolve(filename));
    }

    // =========================================================================
    // Individual KTR tests
    // =========================================================================

    @Test
    void ktr1_filterOrders_splitsByStatus() throws Exception {
        writeInputFiles();
        String basePath = tmp.toString();

        TransformationDefinition def = parseAndWriteKtr(ktr1Xml(basePath), "01_filter_orders.yaml");
        executor().execute(def);

        List<String> confirmed = readOutputLines("confirmed_orders.csv");
        List<String> rejected  = readOutputLines("rejected_orders.csv");

        // Header + 4 confirmed rows (1001, 1003, 1004, 1005)
        assertEquals(5, confirmed.size(), "confirmed: header + 4 rows");
        assertEquals("order_id,customer_id,product_id,quantity,unit_price,status", confirmed.get(0));
        assertTrue(confirmed.stream().anyMatch(l -> l.startsWith("1001,")));
        assertTrue(confirmed.stream().anyMatch(l -> l.startsWith("1003,")));
        assertTrue(confirmed.stream().anyMatch(l -> l.startsWith("1004,")));
        assertTrue(confirmed.stream().anyMatch(l -> l.startsWith("1005,")));

        // Header + 1 cancelled row (1002)
        assertEquals(2, rejected.size(), "rejected: header + 1 row");
        assertEquals("order_id,customer_id,product_id,quantity,unit_price,status", rejected.get(0));
        assertTrue(rejected.get(1).startsWith("1002,"));
    }

    @Test
    void ktr2_enrichCustomers_addsNameAndCountryAndSortsByLastName() throws Exception {
        writeInputFiles();
        String basePath = tmp.toString();

        // KTR 1 must run first to produce confirmed_orders.csv
        executor().execute(parseAndWriteKtr(ktr1Xml(basePath), "01_filter_orders.yaml"));

        TransformationDefinition def2 = parseAndWriteKtr(ktr2Xml(basePath), "02_enrich_customers.yaml");
        executor().execute(def2);

        List<String> lines = readOutputLines("enriched_orders.csv");

        // Header + 4 enriched rows
        assertEquals(5, lines.size(), "enriched: header + 4 rows");
        assertEquals("order_id,customer_id,product_id,quantity,unit_price,status,first_name,last_name,country",
                     lines.get(0));

        // Rows sorted by last_name: Brown(1004), Jones(1005), Smith(1001), Smith(1003)
        assertTrue(lines.get(1).contains("Brown"),  "row 1 should be Carol Brown (1004)");
        assertTrue(lines.get(2).contains("Jones"),  "row 2 should be Bob Jones (1005)");
        assertTrue(lines.get(3).contains("Smith"),  "row 3 should be Alice Smith");
        assertTrue(lines.get(4).contains("Smith"),  "row 4 should be Alice Smith");

        // Verify lookup values are present
        assertTrue(lines.stream().anyMatch(l -> l.contains(",Alice,Smith,UK")));
        assertTrue(lines.stream().anyMatch(l -> l.contains(",Bob,Jones,US")));
        assertTrue(lines.stream().anyMatch(l -> l.contains(",Carol,Brown,UK")));
    }

    @Test
    void ktr3_enrichProducts_addsProductInfoAndSortsByCategory() throws Exception {
        writeInputFiles();
        String basePath = tmp.toString();

        // Run KTR 1 and KTR 2 to produce enriched_orders.csv
        executor().execute(parseAndWriteKtr(ktr1Xml(basePath), "01_filter_orders.yaml"));
        executor().execute(parseAndWriteKtr(ktr2Xml(basePath), "02_enrich_customers.yaml"));

        TransformationDefinition def3 = parseAndWriteKtr(ktr3Xml(basePath), "03_enrich_products.yaml");
        executor().execute(def3);

        List<String> lines = readOutputLines("final_report.csv");

        // Header + 4 rows
        assertEquals(5, lines.size(), "final report: header + 4 rows");
        assertEquals("order_id,first_name,last_name,country,product_name,category,quantity,unit_price",
                     lines.get(0));

        // Sorted by category: Electronics (3 rows) before HomeGoods (1 row)
        long electronics = lines.stream().filter(l -> l.contains("Electronics")).count();
        long homeGoods   = lines.stream().filter(l -> l.contains("HomeGoods")).count();
        assertEquals(3, electronics, "3 Electronics rows");
        assertEquals(1, homeGoods,   "1 HomeGoods row");

        // All Electronics rows come before HomeGoods (categories sorted ascending)
        int lastElec  = lastIndexContaining(lines, "Electronics");
        int firstHome = firstIndexContaining(lines, "HomeGoods");
        assertTrue(lastElec < firstHome, "Electronics rows should precede HomeGoods");

        // Column reordering: order_id,first_name,last_name,country,product_name,category,qty,price
        // Row for Carol Brown / Desk Lamp / HomeGoods
        assertTrue(lines.stream().anyMatch(l ->
                l.startsWith("1004,Carol,Brown,UK,Desk Lamp,HomeGoods,1,79.99")));
        // Row for Bob Jones / Laptop Stand / Electronics
        assertTrue(lines.stream().anyMatch(l ->
                l.startsWith("1005,Bob,Jones,US,Laptop Stand,Electronics,2,149.99")));
    }

    // =========================================================================
    // Full end-to-end KJB test
    // =========================================================================

    @Test
    void fullPipeline_kjbRunsAllThreeKtrsInSequence() throws Exception {
        writeInputFiles();
        String basePath = tmp.toString();

        // Convert all KTRs and KJB to YAML, write to tmp directory
        parseAndWriteKtr(ktr1Xml(basePath), "01_filter_orders.yaml");
        parseAndWriteKtr(ktr2Xml(basePath), "02_enrich_customers.yaml");
        parseAndWriteKtr(ktr3Xml(basePath), "03_enrich_products.yaml");

        JobDefinition jobDef = kjbParser.parse(
                new ByteArrayInputStream(kjbXml(basePath).getBytes(StandardCharsets.UTF_8)));
        String jobYaml = yaml.writeValueAsString(jobDef);
        Files.writeString(tmp.resolve("sales_pipeline.yaml"), jobYaml);

        // Execute the job; basePath tells RunTransformationEntry where to find YAML files;
        // BASE_PATH (already baked into def.parameters) tells KTRs where to find CSV files.
        boolean success = new JobExecutor(JobEntryRegistry.withDefaults())
                .execute(jobDef, Map.of("basePath", basePath));

        assertTrue(success, "Job should complete successfully");

        // All 4 output files must exist
        assertTrue(Files.exists(tmp.resolve("output/confirmed_orders.csv")));
        assertTrue(Files.exists(tmp.resolve("output/rejected_orders.csv")));
        assertTrue(Files.exists(tmp.resolve("output/enriched_orders.csv")));
        assertTrue(Files.exists(tmp.resolve("output/final_report.csv")));

        // Spot-check the final report
        List<String> finalLines = readOutputLines("final_report.csv");
        assertEquals(5, finalLines.size(), "final report: header + 4 rows");
        assertTrue(finalLines.stream().anyMatch(l -> l.contains("Laptop Stand")),
                   "Laptop Stand should appear in final report");
        assertTrue(finalLines.stream().anyMatch(l -> l.contains("Desk Lamp")),
                   "Desk Lamp should appear in final report");
        assertTrue(finalLines.stream().anyMatch(l -> l.contains("Widget Pro")),
                   "Widget Pro should appear twice (orders 1001 and 1003)");

        // Rejected orders: only 1002
        List<String> rejectedLines = readOutputLines("rejected_orders.csv");
        assertEquals(2, rejectedLines.size(), "rejected: header + 1 cancelled row");
        assertTrue(rejectedLines.get(1).startsWith("1002,"));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static int lastIndexContaining(List<String> lines, String s) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (lines.get(i).contains(s)) return i;
        }
        return -1;
    }

    private static int firstIndexContaining(List<String> lines, String s) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(s)) return i;
        }
        return lines.size();
    }
}
