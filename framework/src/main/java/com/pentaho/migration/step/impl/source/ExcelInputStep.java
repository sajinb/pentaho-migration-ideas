package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Reads rows from an Excel file ({@code .xls} or {@code .xlsx}) via Apache POI.
 * Supports both local filesystem and remote SFTP sources.
 *
 * <h3>Local params</h3>
 * <ul>
 *   <li>{@code filePath}  — path to the local Excel file (required when no sftpHost)
 *   <li>{@code sheetName} — sheet to read; defaults to the first sheet
 *   <li>{@code hasHeader} — "true" (default) to skip header row
 * </ul>
 *
 * <h3>SFTP params (when {@code sftpHost} is present)</h3>
 * <ul>
 *   <li>{@code sftpHost}                — SFTP server hostname (enables SFTP mode)
 *   <li>{@code sftpPort}                — port (default: 22)
 *   <li>{@code sftpUser}                — SSH username
 *   <li>{@code sftpPassword}            — password (or use private key)
 *   <li>{@code sftpPrivateKeyPath}      — path to private key file (PEM/OpenSSH)
 *   <li>{@code sftpPrivateKeyPassphrase}— passphrase for encrypted private key
 *   <li>{@code sftpKnownHostsPath}      — known_hosts file (defaults to ~/.ssh/known_hosts)
 *   <li>{@code sftpStrictHostChecking}  — "true" (default) / "false"
 *   <li>{@code filePath}                — absolute path of the Excel file on the SFTP server
 * </ul>
 *
 * <p>All rows are eagerly materialized; the workbook (and SFTP connection) are
 * closed before returning. Null/blank cells are represented as {@code null} in the row.
 */
public class ExcelInputStep extends AbstractSourceStep {

    private String  filePath;
    private String  sheetName;
    private boolean hasHeader;
    private Map<String, String> params;

    @Override
    public void configure(Map<String, String> params) {
        this.params = params;
        filePath  = params.get("filePath");
        sheetName = params.get("sheetName"); // null → first sheet
        hasHeader = !"false".equalsIgnoreCase(params.getOrDefault("hasHeader", "true"));
    }

    @Override
    protected Iterator<Row> readRows() throws Exception {
        if (SftpSource.isSftp(params)) {
            try (InputStream in = SftpSource.from(params).openStream()) {
                return readFromStream(in);
            }
        }
        try (Workbook workbook = WorkbookFactory.create(new File(filePath))) {
            return readFromWorkbook(workbook);
        }
    }

    // -------------------------------------------------------------------------

    private Iterator<Row> readFromStream(InputStream in) throws Exception {
        try (Workbook workbook = WorkbookFactory.create(in)) {
            return readFromWorkbook(workbook);
        }
    }

    private Iterator<Row> readFromWorkbook(Workbook workbook) {
        Sheet sheet = sheetName != null
                ? workbook.getSheet(sheetName)
                : workbook.getSheetAt(0);
        if (sheet == null) {
            throw new IllegalArgumentException(
                    "Sheet not found: " + (sheetName != null ? sheetName : "(first sheet)"));
        }

        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        Iterator<org.apache.poi.ss.usermodel.Row> rowIter = sheet.rowIterator();

        if (hasHeader && rowIter.hasNext()) {
            rowIter.next(); // discard header
        }

        List<Row> rows = new ArrayList<>();
        while (rowIter.hasNext()) {
            org.apache.poi.ss.usermodel.Row poiRow = rowIter.next();
            int cellCount = Math.max(0, poiRow.getLastCellNum());
            String[] values = new String[cellCount];
            for (int i = 0; i < cellCount; i++) {
                Cell cell = poiRow.getCell(i, MissingCellPolicy.RETURN_BLANK_AS_NULL);
                values[i] = cellToString(cell, evaluator);
            }
            rows.add(new Row(values));
        }
        return rows.isEmpty() ? Collections.emptyIterator() : rows.iterator();
    }

    private static String cellToString(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            CellValue cv = evaluator.evaluate(cell);
            type = cv.getCellType();
            return switch (type) {
                case NUMERIC  -> String.valueOf(cv.getNumberValue());
                case STRING   -> cv.getStringValue();
                case BOOLEAN  -> String.valueOf(cv.getBooleanValue());
                default       -> null;
            };
        }
        return switch (type) {
            case NUMERIC  -> String.valueOf(cell.getNumericCellValue());
            case STRING   -> cell.getStringCellValue();
            case BOOLEAN  -> String.valueOf(cell.getBooleanCellValue());
            case BLANK    -> null;
            default       -> null;
        };
    }
}
