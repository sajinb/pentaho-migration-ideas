package com.pentaho.migration.step.impl.sink;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSinkStep;
import org.apache.poi.ss.usermodel.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * Appends rows to a named sheet in an <em>existing</em> {@code .xlsx} file.
 * Equivalent to Pentaho's TypeExitExcelWriter step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code filePath}    — path to an existing Excel file (required)
 *   <li>{@code sheetName}   — sheet to append to; created if not found (default: first sheet)
 *   <li>{@code writeHeader} — if {@code "true"} (default), write a {@code col0,col1,...} header
 *                             before the first appended data row (only when appending to an
 *                             empty / newly-created sheet)
 * </ul>
 *
 * <p>Rows are appended after the last existing row in the target sheet.
 * The file is overwritten in-place on {@link #close()}.
 */
public class TypeExitExcelWriterStep extends AbstractSinkStep {

    private String  filePath;
    private String  sheetName;
    private boolean writeHeader;

    private Workbook workbook;
    private Sheet    sheet;
    private int      rowIndex;
    private boolean  headerWritten;

    @Override
    public void configure(Map<String, String> params) {
        filePath    = params.get("filePath");
        sheetName   = params.get("sheetName"); // null → first sheet
        writeHeader = !"false".equalsIgnoreCase(params.getOrDefault("writeHeader", "true"));
    }

    @Override
    protected void open() throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IllegalArgumentException(
                    "TypeExitExcelWriterStep: file not found: " + filePath
                    + " — use ExcelOutputStep to create a new file.");
        }
        // Use FileInputStream so POI buffers the entire file internally,
        // releasing the original file handle. This allows safe overwrite in close().
        try (FileInputStream fis = new FileInputStream(file)) {
            workbook = WorkbookFactory.create(fis);
        }

        if (sheetName != null) {
            sheet = workbook.getSheet(sheetName);
            if (sheet == null) {
                sheet = workbook.createSheet(sheetName);
            }
        } else {
            sheet = workbook.getSheetAt(0);
        }

        // Determine next row index (append after existing content)
        int lastRow = sheet.getLastRowNum();
        rowIndex = (sheet.getPhysicalNumberOfRows() == 0) ? 0 : lastRow + 1;
        headerWritten = (rowIndex > 0); // don't re-write header if sheet already has rows
    }

    @Override
    protected void writeRow(Row row) throws Exception {
        if (writeHeader && !headerWritten) {
            org.apache.poi.ss.usermodel.Row header = sheet.createRow(rowIndex++);
            for (int i = 0; i < row.fieldCount(); i++) {
                header.createCell(i).setCellValue("col" + i);
            }
            headerWritten = true;
        }
        org.apache.poi.ss.usermodel.Row poiRow = sheet.createRow(rowIndex++);
        for (int i = 0; i < row.fieldCount(); i++) {
            String v = row.getString(i);
            poiRow.createCell(i).setCellValue(v != null ? v : "");
        }
    }

    @Override
    protected void close() throws Exception {
        try (FileOutputStream fos = new FileOutputStream(filePath)) {
            workbook.write(fos);
        } finally {
            workbook.close();
        }
    }
}
