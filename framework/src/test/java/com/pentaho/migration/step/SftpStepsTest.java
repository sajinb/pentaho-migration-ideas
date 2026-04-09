package com.pentaho.migration.step;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.impl.source.CsvInputStep;
import com.pentaho.migration.step.impl.source.ExcelInputStep;
import com.pentaho.migration.step.impl.source.SftpSource;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.auth.password.StaticPasswordAuthenticator;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for SFTP support in CsvInputStep and ExcelInputStep.
 * Uses an embedded Apache MINA SSHD server so no external SFTP server is needed.
 */
class SftpStepsTest {

    @TempDir static Path sftpRoot;
    @TempDir static Path hostKeyDir;

    private static SshServer sshd;
    private static int        port;

    // ── Server lifecycle ──────────────────────────────────────────────────────

    @BeforeAll
    static void startSftpServer() throws Exception {
        sshd = SshServer.setUpDefaultServer();
        sshd.setPort(0); // OS-assigned free port

        // Host key (ephemeral — generated fresh each test run)
        Path hostKey = hostKeyDir.resolve("hostkey.ser");
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));

        // Password auth: only accept user "testuser" / "testpass"
        sshd.setPasswordAuthenticator(new StaticPasswordAuthenticator(true));

        // Serve the temp directory over SFTP
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(sftpRoot));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));

        sshd.start();
        port = sshd.getPort();
    }

    @AfterAll
    static void stopSftpServer() throws Exception {
        if (sshd != null) sshd.stop();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Map<String, String> sftpParams(String remotePath) {
        Map<String, String> p = new HashMap<>();
        p.put("sftpHost",               "127.0.0.1");
        p.put("sftpPort",               String.valueOf(port));
        p.put("sftpUser",               "testuser");
        p.put("sftpPassword",           "testpass");
        p.put("sftpStrictHostChecking", "false");
        p.put("filePath",               remotePath);
        return p;
    }

    // ── SftpSource.isSftp() unit tests ────────────────────────────────────────

    @Test
    void isSftp_returnsTrueWhenHostPresent() {
        assertTrue(SftpSource.isSftp(Map.of("sftpHost", "myserver.example.com",
                                             "filePath", "/data/file.csv",
                                             "sftpUser", "user",
                                             "sftpPassword", "pw")));
    }

    @Test
    void isSftp_returnsFalseWhenHostAbsent() {
        assertFalse(SftpSource.isSftp(Map.of("filePath", "/local/file.csv")));
    }

    @Test
    void isSftp_returnsFalseWhenHostBlank() {
        assertFalse(SftpSource.isSftp(Map.of("sftpHost", "  ", "filePath", "/f.csv")));
    }

    // ── CsvInputStep — SFTP ───────────────────────────────────────────────────

    @Test
    void csvInput_sftp_readsHeaderAndDataRows() throws Exception {
        // Create a CSV file in the fake SFTP root
        Path remoteCsv = sftpRoot.resolve("data.csv");
        Files.writeString(remoteCsv,
                "name,age\n" +
                "Alice,30\n" +
                "Bob,25\n");

        Map<String, String> params = sftpParams("/data.csv");
        CsvInputStep step = new CsvInputStep();
        step.configure(params);

        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(2, rows.size());
        assertEquals("Alice", rows.get(0).getString(0));
        assertEquals("30",    rows.get(0).getString(1));
        assertEquals("Bob",   rows.get(1).getString(0));
        assertEquals("25",    rows.get(1).getString(1));
    }

    @Test
    void csvInput_sftp_noHeader_includesAllRows() throws Exception {
        Path remoteCsv = sftpRoot.resolve("noheader.csv");
        Files.writeString(remoteCsv, "X,1\nY,2\n");

        Map<String, String> params = sftpParams("/noheader.csv");
        params.put("hasHeader", "false");

        CsvInputStep step = new CsvInputStep();
        step.configure(params);
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(2, rows.size());
        assertEquals("X", rows.get(0).getString(0));
        assertEquals("Y", rows.get(1).getString(0));
    }

    @Test
    void csvInput_sftp_emptyFile_returnsNoRows() throws Exception {
        Path remoteCsv = sftpRoot.resolve("empty.csv");
        Files.writeString(remoteCsv, "col1,col2\n"); // header only

        CsvInputStep step = new CsvInputStep();
        step.configure(sftpParams("/empty.csv"));
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(0, rows.size());
    }

    // ── ExcelInputStep — SFTP ────────────────────────────────────────────────

    @Test
    void excelInput_sftp_readsHeaderAndDataRows() throws Exception {
        Path remoteXlsx = sftpRoot.resolve("report.xlsx");
        createXlsx(remoteXlsx, new String[]{"city", "pop"},
                   new String[][]{ {"London", "9000000"}, {"Paris", "2100000"} });

        ExcelInputStep step = new ExcelInputStep();
        step.configure(sftpParams("/report.xlsx"));
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(2, rows.size());
        assertEquals("London",  rows.get(0).getString(0));
        assertEquals("9000000", rows.get(0).getString(1));
        assertEquals("Paris",   rows.get(1).getString(0));
    }

    @Test
    void excelInput_sftp_specificSheet() throws Exception {
        Path remoteXlsx = sftpRoot.resolve("multi.xlsx");
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(remoteXlsx.toFile())) {
            // Sheet 1: dummy
            org.apache.poi.ss.usermodel.Sheet s1 = wb.createSheet("Ignore");
            org.apache.poi.ss.usermodel.Row r0 = s1.createRow(0);
            r0.createCell(0).setCellValue("ignore");

            // Sheet 2: actual data
            org.apache.poi.ss.usermodel.Sheet s2 = wb.createSheet("Sales");
            org.apache.poi.ss.usermodel.Row hdr = s2.createRow(0);
            hdr.createCell(0).setCellValue("product");
            org.apache.poi.ss.usermodel.Row d1 = s2.createRow(1);
            d1.createCell(0).setCellValue("Widget");

            wb.write(fos);
        }

        Map<String, String> params = sftpParams("/multi.xlsx");
        params.put("sheetName", "Sales");

        ExcelInputStep step = new ExcelInputStep();
        step.configure(params);
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(1, rows.size());
        assertEquals("Widget", rows.get(0).getString(0));
    }

    @Test
    void excelInput_sftp_noHeader_includesAllRows() throws Exception {
        Path remoteXlsx = sftpRoot.resolve("noheader.xlsx");
        createXlsx(remoteXlsx, null, new String[][]{ {"A", "1"}, {"B", "2"} });

        Map<String, String> params = sftpParams("/noheader.xlsx");
        params.put("hasHeader", "false");

        ExcelInputStep step = new ExcelInputStep();
        step.configure(params);
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(2, rows.size());
        assertEquals("A", rows.get(0).getString(0));
    }

    // ── CsvInputStep — local (regression guard) ───────────────────────────────

    @Test
    void csvInput_local_stillWorks(@TempDir Path tmp) throws Exception {
        Path csv = tmp.resolve("local.csv");
        Files.writeString(csv, "x,y\n1,2\n3,4\n");

        CsvInputStep step = new CsvInputStep();
        step.configure(Map.of("filePath", csv.toString()));
        List<Row> rows = collectRows(step.apply(List.of()));

        assertEquals(2, rows.size());
        assertEquals("1", rows.get(0).getString(0));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<Row> collectRows(Iterator<Row> iter) {
        List<Row> out = new ArrayList<>();
        while (iter.hasNext()) out.add(iter.next());
        return out;
    }

    /** Creates an .xlsx file with optional header and data rows (string values). */
    private static void createXlsx(Path path, String[] header, String[][] data) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook();
             FileOutputStream fos = new FileOutputStream(path.toFile())) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.createSheet("Sheet1");
            int rowIdx = 0;
            if (header != null) {
                org.apache.poi.ss.usermodel.Row hrow = sheet.createRow(rowIdx++);
                for (int i = 0; i < header.length; i++)
                    hrow.createCell(i).setCellValue(header[i]);
            }
            for (String[] drow : data) {
                org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIdx++);
                for (int i = 0; i < drow.length; i++)
                    row.createCell(i).setCellValue(drow[i]);
            }
            wb.write(fos);
        }
    }
}
