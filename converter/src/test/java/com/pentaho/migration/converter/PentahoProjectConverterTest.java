package com.pentaho.migration.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.model.JobDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class PentahoProjectConverterTest {

    private final PentahoProjectConverter converter = new PentahoProjectConverter();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory());

    @TempDir Path tmp;

    // -------------------------------------------------------------------------
    // Helper KTR / KJB XML strings
    // -------------------------------------------------------------------------

    private static final String SIMPLE_KTR = """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>sort_pipeline</name></info>
              <step>
                <name>read</name><type>CSVInput</type>
                <filename>/data/in.csv</filename><header>Y</header>
              </step>
              <step>
                <name>write</name><type>TextFileOutput</type>
                <file><name>/data/out.csv</name></file>
              </step>
              <order>
                <hop><from>read</from><to>write</to><enabled>Y</enabled></hop>
              </order>
            </transformation>
            """;

    private static final String SIMPLE_KJB = """
            <?xml version="1.0" encoding="UTF-8"?>
            <job>
              <name>daily_job</name>
              <entries>
                <entry><name>START</name><type>SPECIAL</type><start>Y</start></entry>
                <entry>
                  <name>run</name><type>TRANS</type>
                  <filename>sort_pipeline.ktr</filename>
                </entry>
                <entry><name>SUCCESS</name><type>SPECIAL</type><success>Y</success></entry>
              </entries>
              <hops>
                <hop><from>START</from><to>run</to><enabled>Y</enabled><unconditional>Y</unconditional></hop>
                <hop><from>run</from><to>SUCCESS</to><enabled>Y</enabled>
                     <evaluation>true</evaluation><unconditional>N</unconditional></hop>
              </hops>
            </job>
            """;

    // -------------------------------------------------------------------------
    // 1. convertKtr → valid YAML round-trip
    // -------------------------------------------------------------------------

    @Test
    void convertKtr_producesValidYaml() throws Exception {
        String yamlStr = converter.convertKtr(toStream(SIMPLE_KTR));

        assertNotNull(yamlStr);
        assertFalse(yamlStr.isBlank());

        TransformationDefinition def = yaml.readValue(yamlStr, TransformationDefinition.class);
        assertEquals("sort_pipeline", def.name);
        assertEquals(2, def.steps.size());
        assertEquals(1, def.hops.size());
        assertEquals("CsvInput", def.steps.get(0).type);
        assertEquals("/data/in.csv", def.steps.get(0).params.get("filePath"));
    }

    // -------------------------------------------------------------------------
    // 2. convertKjb → valid YAML round-trip
    // -------------------------------------------------------------------------

    @Test
    void convertKjb_producesValidYaml() throws Exception {
        String yamlStr = converter.convertKjb(toStream(SIMPLE_KJB));

        assertNotNull(yamlStr);
        assertFalse(yamlStr.isBlank());

        JobDefinition def = yaml.readValue(yamlStr, JobDefinition.class);
        assertEquals("daily_job", def.name);
        assertEquals(3, def.entries.size());
        assertEquals(2, def.hops.size());

        assertEquals("Start",            def.entries.get(0).type);
        assertEquals("RunTransformation",def.entries.get(1).type);
        assertEquals("sort_pipeline.yaml", def.entries.get(1).params.get("transformationPath"));
        assertEquals("unconditional",    def.hops.get(0).evaluation);
        assertEquals("success",          def.hops.get(1).evaluation);
    }

    // -------------------------------------------------------------------------
    // 3. convert(zip) → output zip contains YAML files
    // -------------------------------------------------------------------------

    @Test
    void convertZip_producesYamlZip() throws Exception {
        // Build input zip with 1 KJB + 1 KTR
        Path inputZip  = tmp.resolve("project.zip");
        Path outputZip = tmp.resolve("converted.zip");

        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(inputZip))) {
            writeZipEntry(zout, "daily_job.kjb",          SIMPLE_KJB);
            writeZipEntry(zout, "transformations/sort.ktr", SIMPLE_KTR);
        }

        converter.convert(inputZip, outputZip);

        // Verify output zip contents
        Map<String, String> outputEntries = readZipEntries(outputZip);

        assertEquals(2, outputEntries.size(), "Expected 2 YAML files in output zip");
        assertTrue(outputEntries.containsKey("daily_job.yaml"),
                "KJB should be converted to daily_job.yaml");
        assertTrue(outputEntries.containsKey("transformations/sort.yaml"),
                "KTR should be converted to transformations/sort.yaml");

        // Spot-check KTR YAML content
        TransformationDefinition tDef = yaml.readValue(
                outputEntries.get("transformations/sort.yaml"), TransformationDefinition.class);
        assertEquals("sort_pipeline", tDef.name);
        assertEquals(2, tDef.steps.size());

        // Spot-check KJB YAML content
        JobDefinition jDef = yaml.readValue(
                outputEntries.get("daily_job.yaml"), JobDefinition.class);
        assertEquals("daily_job", jDef.name);
        assertEquals(3, jDef.entries.size());
    }

    // -------------------------------------------------------------------------
    // 4. Non-.ktr/.kjb entries in zip are skipped
    // -------------------------------------------------------------------------

    @Test
    void convertZip_nonPentahoFilesSkipped() throws Exception {
        Path inputZip  = tmp.resolve("mixed.zip");
        Path outputZip = tmp.resolve("mixed_out.zip");

        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(inputZip))) {
            writeZipEntry(zout, "sort.ktr",     SIMPLE_KTR);
            writeZipEntry(zout, "README.txt",   "some readme text");
            writeZipEntry(zout, "config.xml",   "<config/>");
        }

        converter.convert(inputZip, outputZip);

        Map<String, String> outputEntries = readZipEntries(outputZip);
        assertEquals(1, outputEntries.size(), "Only the .ktr should be converted");
        assertTrue(outputEntries.containsKey("sort.yaml"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ByteArrayInputStream toStream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeZipEntry(ZipOutputStream zout, String name, String content)
            throws Exception {
        zout.putNextEntry(new ZipEntry(name));
        zout.write(content.getBytes(StandardCharsets.UTF_8));
        zout.closeEntry();
    }

    private static Map<String, String> readZipEntries(Path zip) throws Exception {
        Map<String, String> entries = new HashMap<>();
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] bytes = zin.readAllBytes();
                    entries.put(entry.getName(), new String(bytes, StandardCharsets.UTF_8));
                }
                zin.closeEntry();
            }
        }
        return entries;
    }
}
