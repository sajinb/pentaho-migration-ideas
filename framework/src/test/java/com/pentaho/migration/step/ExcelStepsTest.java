package com.pentaho.migration.step;

import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Excel step implementations
 * (ExcelInputStep, ExcelOutputStep, TypeExitExcelWriterStep).
 */
class ExcelStepsTest {

    @TempDir Path tmp;

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

    /**
     * Creates an .xlsx file with the given rows. First row is treated as a header.
     * @param rows  each element is one row; first element is the header row
     */
    private Path createXlsxFile(String name, String sheetName, String[]... rows) throws Exception {
        Path path = tmp.resolve(name);
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(sheetName);
            for (int ri = 0; ri < rows.length; ri++) {
                Row poiRow = sheet.createRow(ri);
                for (int ci = 0; ci < rows[ri].length; ci++) {
                    poiRow.createCell(ci).setCellValue(rows[ri][ci]);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                wb.write(fos);
            }
        }
        return path;
    }

    /** Read all data rows from a sheet (skipping row 0 as header). */
    private List<String[]> readXlsx(Path path, String sheetName) throws Exception {
        List<String[]> result = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(path.toFile())) {
            Sheet sheet = sheetName != null ? wb.getSheet(sheetName) : wb.getSheetAt(0);
            Iterator<Row> iter = sheet.rowIterator();
            if (iter.hasNext()) iter.next(); // skip header
            while (iter.hasNext()) {
                Row r = iter.next();
                int cells = Math.max(0, r.getLastCellNum());
                String[] vals = new String[cells];
                for (int i = 0; i < cells; i++) {
                    Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    vals[i] = (c == null) ? null : c.getStringCellValue();
                }
                result.add(vals);
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // 1. ExcelInput reads rows (skipping header)
    // -------------------------------------------------------------------------

    @Test
    void excelInputReadsRows() throws Exception {
        Path xlsx = createXlsxFile("input.xlsx", "Sheet1",
            new String[]{"name", "city"},
            new String[]{"Alice", "London"},
            new String[]{"Bob",   "Paris"},
            new String[]{"Carol", "Rome"}
        );

        Path output = tmp.resolve("excel_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "excelRead";
        def.steps = List.of(
            step("src", "ExcelInput",     Map.of("filePath", xlsx.toString())),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(3, rows.size());
        assertEquals("Alice",  rows.get(0)[0]);
        assertEquals("London", rows.get(0)[1]);
        assertEquals("Bob",    rows.get(1)[0]);
        assertEquals("Carol",  rows.get(2)[0]);
    }

    // -------------------------------------------------------------------------
    // 2. ExcelInput with hasHeader=false includes the first row
    // -------------------------------------------------------------------------

    @Test
    void excelInputNoHeader() throws Exception {
        Path xlsx = createXlsxFile("noheader.xlsx", "Sheet1",
            new String[]{"Alice", "London"},
            new String[]{"Bob",   "Paris"}
        );

        Path output = tmp.resolve("noheader_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "excelNoHeader";
        def.steps = List.of(
            step("src", "ExcelInput",     Map.of("filePath", xlsx.toString(), "hasHeader", "false")),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(2, rows.size());  // both rows included (no header skipped)
        assertEquals("Alice", rows.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // 3. ExcelInput reads from a named sheet
    // -------------------------------------------------------------------------

    @Test
    void excelInputNamedSheet() throws Exception {
        Path xlsx = tmp.resolve("multi.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s1 = wb.createSheet("First");
            s1.createRow(0).createCell(0).setCellValue("wrong");

            Sheet s2 = wb.createSheet("Data");
            s2.createRow(0).createCell(0).setCellValue("header");
            s2.createRow(1).createCell(0).setCellValue("correct");
            try (FileOutputStream fos = new FileOutputStream(xlsx.toFile())) {
                wb.write(fos);
            }
        }

        Path output = tmp.resolve("named_out.csv");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "namedSheet";
        def.steps = List.of(
            step("src", "ExcelInput",     Map.of("filePath", xlsx.toString(), "sheetName", "Data")),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(1, rows.size());
        assertEquals("correct", rows.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // 4. ExcelOutput creates a new .xlsx file
    // -------------------------------------------------------------------------

    @Test
    void excelOutputCreatesFile() throws Exception {
        Path input  = writeCsv("data.csv", "Alice,90", "Bob,85", "Carol,92");
        Path output = tmp.resolve("created.xlsx");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "excelWrite";
        def.steps = List.of(
            step("src", "CsvInput",    Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("out", "ExcelOutput", Map.of("filePath", output.toString(),
                                              "writeHeader", "false"))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        assertTrue(Files.exists(output));

        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(output.toFile())) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                int cells = Math.max(0, r.getLastCellNum());
                String[] vals = new String[cells];
                for (int i = 0; i < cells; i++) {
                    Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    vals[i] = (c == null) ? "" : c.getStringCellValue();
                }
                rows.add(vals);
            }
        }

        assertEquals(3, rows.size());
        assertEquals("Alice", rows.get(0)[0]);
        assertEquals("90",    rows.get(0)[1]);
        assertEquals("Bob",   rows.get(1)[0]);
    }

    // -------------------------------------------------------------------------
    // 5. TypeExitExcelWriterStep appends rows to an existing sheet
    // -------------------------------------------------------------------------

    @Test
    void typeExitExcelWriterAppendsRows() throws Exception {
        // Pre-create an xlsx with 2 existing data rows (+ 1 header)
        Path xlsx = createXlsxFile("existing.xlsx", "Sheet1",
            new String[]{"name"},
            new String[]{"OldRow1"},
            new String[]{"OldRow2"}
        );

        Path input = writeCsv("append.csv", "NewRow1", "NewRow2", "NewRow3");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "appendExcel";
        def.steps = List.of(
            step("src", "CsvInput",            Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("out", "TypeExitExcelWriter",  Map.of("filePath", xlsx.toString(),
                                                       "writeHeader", "false"))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        // Re-open and count all rows (header + 2 old + 3 new = 6)
        try (Workbook wb = WorkbookFactory.create(xlsx.toFile())) {
            Sheet sheet = wb.getSheetAt(0);
            int rowCount = sheet.getPhysicalNumberOfRows();
            assertEquals(6, rowCount);
        }
    }

    // -------------------------------------------------------------------------
    // 6. Excel round-trip: ExcelInput → ExcelOutput → re-read and verify
    // -------------------------------------------------------------------------

    @Test
    void excelRoundTrip() throws Exception {
        Path original = createXlsxFile("round_in.xlsx", "Sheet1",
            new String[]{"id", "value"},
            new String[]{"1",  "alpha"},
            new String[]{"2",  "beta"},
            new String[]{"3",  "gamma"}
        );

        Path roundOut = tmp.resolve("round_out.xlsx");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "roundTrip";
        def.steps = List.of(
            step("src", "ExcelInput",  Map.of("filePath", original.toString())),
            step("out", "ExcelOutput", Map.of("filePath", roundOut.toString(),
                                              "writeHeader", "false"))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        // Read back without header skip (writeHeader=false means no header was written)
        List<String[]> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(roundOut.toFile())) {
            Sheet sheet = wb.getSheetAt(0);
            for (Row r : sheet) {
                int cells = Math.max(0, r.getLastCellNum());
                String[] vals = new String[cells];
                for (int i = 0; i < cells; i++) {
                    Cell c = r.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    vals[i] = (c == null) ? "" : c.getStringCellValue();
                }
                rows.add(vals);
            }
        }

        assertEquals(3, rows.size());
        assertEquals("1",     rows.get(0)[0]);
        assertEquals("alpha", rows.get(0)[1]);
        assertEquals("2",     rows.get(1)[0]);
        assertEquals("3",     rows.get(2)[0]);
        assertEquals("gamma", rows.get(2)[1]);
    }
}
