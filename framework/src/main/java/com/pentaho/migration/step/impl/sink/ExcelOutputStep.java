package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSinkStep;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Writes rows to a new {@code .xlsx} Excel file.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath}    — output file path (required)
 *   <li>{@code sheetName}   — sheet name (default {@code "Sheet1"})
 *   <li>{@code writeHeader} — if {@code "true"} (default), write a {@code col0,col1,...} header row
 *                             before the first data row
 * </ul>
 */
public class ExcelOutputStep extends AbstractSinkStep {

    private String   filePath;
    private String   sheetName;
    private boolean  writeHeader;

    protected Workbook workbook;
    protected Sheet    sheet;
    protected int      rowIndex;

    @Override
    public void configure(Map<String, String> params) {
        filePath    = params.get("filePath");
        sheetName   = params.getOrDefault("sheetName", "Sheet1");
        writeHeader = !"false".equalsIgnoreCase(params.getOrDefault("writeHeader", "true"));
    }

    @Override
    protected void open() throws Exception {
        workbook = new XSSFWorkbook();
        sheet    = workbook.createSheet(sheetName);
        rowIndex = 0;
    }

    @Override
    protected void writeRow(Row row) throws Exception {
        if (writeHeader && rowIndex == 0) {
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < row.fieldCount(); i++) {
                header.createCell(i).setCellValue("col" + i);
            }
        }
        org.apache.poi.ss.usermodel.Row poiRow = sheet.createRow(rowIndex++);
        for (int i = 0; i < row.fieldCount(); i++) {
            String v = row.getString(i);
            poiRow.createCell(i).setCellValue(v != null ? v : "");
        }
    }

    @Override
    protected void close() throws Exception {
        try (OutputStream out = Files.newOutputStream(Paths.get(filePath))) {
            workbook.write(out);
        } finally {
            workbook.close();
        }
    }
}
