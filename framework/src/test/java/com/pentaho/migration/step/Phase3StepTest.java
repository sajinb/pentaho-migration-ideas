package com.pentaho.migration.step;

import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for Phase 3 steps: ScriptValueModStep (Rhino JS) and
 * UserDefinedJavaClassStep (Janino runtime compile).
 *
 * <p>Both tests run a mini-pipeline: CsvInput → [Phase3Step] → TextFileOutput,
 * then assert the output CSV contains the expected transformed values.
 */
class Phase3StepTest {

    @TempDir
    Path tmp;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static StepDefinition step(String id, String type, Map<String, String> params) {
        StepDefinition s = new StepDefinition();
        s.id = id; s.type = type; s.params = params;
        return s;
    }

    private static HopDefinition hop(String from, String to) {
        HopDefinition h = new HopDefinition();
        h.from = from; h.to = to; h.enabled = true;
        return h;
    }

    private static TransformationDefinition pipeline(List<StepDefinition> steps, List<HopDefinition> hops) {
        TransformationDefinition def = new TransformationDefinition();
        def.name  = "test";
        def.steps = steps;
        def.hops  = hops;
        return def;
    }

    private Path writeCsv(String... lines) throws Exception {
        Path f = tmp.resolve("input.csv");
        Files.writeString(f, String.join("\n", lines) + "\n");
        return f;
    }

    // -------------------------------------------------------------------------
    // ScriptValueModStep tests
    // -------------------------------------------------------------------------

    @Test
    void scriptValueMod_addsNewField() throws Exception {
        // Input: name,score → script appends "grade" (A/B/C)
        Path input  = writeCsv("name,score", "Alice,95", "Bob,72", "Charlie,55");
        Path output = tmp.resolve("out.csv");

        String script = """
                var grade;
                if (parseFloat(score) >= 90) { grade = "A"; }
                else if (parseFloat(score) >= 70) { grade = "B"; }
                else { grade = "C"; }
                """;

        var def = pipeline(
            List.of(
                step("in",  "CsvInput",        Map.of("filePath", input.toString(), "hasHeader", "true")),
                step("js",  "ScriptValueMod",  Map.of(
                        "script",       script,
                        "fieldNames",   "name,score",
                        "outputFields", "grade")),
                step("out", "TextFileOutput",   Map.of("filePath", output.toString(), "writeHeader", "true",
                                                        "separator", ","))
            ),
            List.of(hop("in", "js"), hop("js", "out"))
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        String csv = Files.readString(output);
        assertTrue(csv.contains("A"),  "Alice should be grade A");
        assertTrue(csv.contains("B"),  "Bob should be grade B");
        assertTrue(csv.contains("C"),  "Charlie should be grade C");
    }

    @Test
    void scriptValueMod_updatesExistingField() throws Exception {
        // Script uppercases the 'name' field in-place
        Path input  = writeCsv("name,value", "alice,1", "bob,2");
        Path output = tmp.resolve("out.csv");

        String script = "var name = name.toUpperCase();";

        var def = pipeline(
            List.of(
                step("in",  "CsvInput",       Map.of("filePath", input.toString(), "hasHeader", "true")),
                step("js",  "ScriptValueMod", Map.of(
                        "script",       script,
                        "fieldNames",   "name,value",
                        "outputFields", "name")),
                step("out", "TextFileOutput",  Map.of("filePath", output.toString(), "writeHeader", "true",
                                                       "separator", ","))
            ),
            List.of(hop("in", "js"), hop("js", "out"))
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        String csv = Files.readString(output);
        assertFalse(csv.contains("alice"), "lowercase 'alice' should not appear");
        assertTrue(csv.contains("ALICE"),  "uppercase 'ALICE' should appear");
        assertTrue(csv.contains("BOB"),    "uppercase 'BOB' should appear");
    }

    @Test
    void scriptValueMod_nullInputHandled() throws Exception {
        // Script handles null field gracefully
        Path input  = writeCsv("name", "hello", "");
        Path output = tmp.resolve("out.csv");

        String script = """
                var upper = (name != null && name != '') ? name.toUpperCase() : 'EMPTY';
                """;

        var def = pipeline(
            List.of(
                step("in",  "CsvInput",       Map.of("filePath", input.toString(), "hasHeader", "true")),
                step("js",  "ScriptValueMod", Map.of(
                        "script",       script,
                        "fieldNames",   "name",
                        "outputFields", "upper")),
                step("out", "TextFileOutput",  Map.of("filePath", output.toString(), "writeHeader", "true",
                                                       "separator", ","))
            ),
            List.of(hop("in", "js"), hop("js", "out"))
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        String csv = Files.readString(output);
        assertTrue(csv.contains("HELLO"), "non-null input should be uppercased");
        assertTrue(csv.contains("EMPTY"), "empty/null input should produce EMPTY");
    }

    // -------------------------------------------------------------------------
    // UserDefinedJavaClassStep tests
    // -------------------------------------------------------------------------

    @Test
    void userDefinedJavaClass_appendsNewField() throws Exception {
        // Script appends the reversed name
        Path input  = writeCsv("name", "Alice", "Bob");
        Path output = tmp.resolve("out.csv");

        String javaScript = """
                String name = fields[0];
                String reversed = (name != null) ? new StringBuilder(name).reverse().toString() : null;
                String[] result = new String[fields.length + 1];
                System.arraycopy(fields, 0, result, 0, fields.length);
                result[fields.length] = reversed;
                return result;
                """;

        var def = pipeline(
            List.of(
                step("in",   "CsvInput",                Map.of("filePath", input.toString(), "hasHeader", "true")),
                step("java", "UserDefinedJavaClass",    Map.of(
                        "script",     javaScript,
                        "fieldNames", "name")),
                step("out",  "TextFileOutput",           Map.of("filePath", output.toString(), "writeHeader", "false",
                                                                  "separator", ","))
            ),
            List.of(hop("in", "java"), hop("java", "out"))
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        String csv = Files.readString(output);
        assertTrue(csv.contains("ecilA"), "Alice reversed = ecilA");
        assertTrue(csv.contains("boB"),   "Bob reversed = boB");
    }

    @Test
    void userDefinedJavaClass_canModifyExistingField() throws Exception {
        // Script trims and lowercases
        Path input  = writeCsv("name", "  Alice  ", "BOB");
        Path output = tmp.resolve("out.csv");

        String javaScript = """
                String[] result = new String[fields.length];
                result[0] = (fields[0] != null) ? fields[0].trim().toLowerCase() : null;
                return result;
                """;

        var def = pipeline(
            List.of(
                step("in",   "CsvInput",             Map.of("filePath", input.toString(), "hasHeader", "true")),
                step("java", "UserDefinedJavaClass",  Map.of(
                        "script",     javaScript,
                        "fieldNames", "name")),
                step("out",  "TextFileOutput",         Map.of("filePath", output.toString(), "writeHeader", "false",
                                                               "separator", ","))
            ),
            List.of(hop("in", "java"), hop("java", "out"))
        );

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        String csv = Files.readString(output);
        assertTrue(csv.contains("alice"), "should be lowercase trimmed");
        assertTrue(csv.contains("bob"),   "BOB should become bob");
    }

    @Test
    void userDefinedJavaClass_compilationError_throwsOnConfigure() {
        com.pentaho.migration.step.impl.streaming.UserDefinedJavaClassStep step =
                new com.pentaho.migration.step.impl.streaming.UserDefinedJavaClassStep();
        assertThrows(IllegalArgumentException.class, () ->
            step.configure(Map.of("script", "this is not valid java code !!!"))
        );
    }
}
