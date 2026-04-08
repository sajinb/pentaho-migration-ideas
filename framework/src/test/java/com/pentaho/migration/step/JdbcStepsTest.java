package com.pentaho.migration.step;

import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.sort.Row;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the JDBC step implementations (TableInput, TableOutput,
 * InsertUpdate, Delete, ExecSQL).
 *
 * <p>Each test uses a fresh H2 in-memory database to ensure isolation.
 * H2's {@code DB_CLOSE_DELAY=-1} keeps the database open between connections.
 */
class JdbcStepsTest {

    @TempDir Path tmp;

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private static String freshDbUrl() {
        return "jdbc:h2:mem:testdb_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    private static Map<String, String> jdbcParams(String url) {
        Map<String, String> p = new HashMap<>();
        p.put("jdbcDriver",   "org.h2.Driver");
        p.put("jdbcUrl",      url);
        p.put("jdbcUser",     "sa");
        p.put("jdbcPassword", "");
        return p;
    }

    /** Creates a table with the given string columns and inserts the provided rows. */
    private static Connection openConn(String url) throws SQLException {
        try { Class.forName("org.h2.Driver"); } catch (ClassNotFoundException ignored) {}
        return DriverManager.getConnection(url, "sa", "");
    }

    private static void createTable(Connection conn, String table, String... cols)
            throws SQLException {
        StringBuilder sb = new StringBuilder("CREATE TABLE " + table + " (");
        for (int i = 0; i < cols.length; i++) {
            sb.append(cols[i]).append(" VARCHAR(255)");
            if (i < cols.length - 1) sb.append(", ");
        }
        sb.append(")");
        conn.createStatement().executeUpdate(sb.toString());
        conn.commit();
    }

    private static void insertRow(Connection conn, String table, String... values)
            throws SQLException {
        String placeholders = String.join(", ", Collections.nCopies(values.length, "?"));
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO " + table + " VALUES (" + placeholders + ")")) {
            for (int i = 0; i < values.length; i++) ps.setString(i + 1, values[i]);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private static List<String[]> queryAll(Connection conn, String table) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (ResultSet rs = conn.createStatement().executeQuery("SELECT * FROM " + table)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                String[] row = new String[cols];
                for (int i = 0; i < cols; i++) row[i] = rs.getString(i + 1);
                rows.add(row);
            }
        }
        return rows;
    }

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

    // -------------------------------------------------------------------------
    // 1. TableInput — reads all rows from a table
    // -------------------------------------------------------------------------

    @Test
    void tableInputSelectsAllRows() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "CUSTOMERS", "name", "city");
            insertRow(conn, "CUSTOMERS", "Alice", "London");
            insertRow(conn, "CUSTOMERS", "Bob",   "Paris");
            insertRow(conn, "CUSTOMERS", "Carol", "Rome");
        }

        Path output = tmp.resolve("tableout.csv");
        Map<String, String> params = jdbcParams(url);
        params.put("tableName", "CUSTOMERS");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "tableInput";
        def.steps = List.of(
            step("src", "TableInput",     params),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(3, rows.size());
        assertEquals("Alice", rows.get(0)[0]);
        assertEquals("Paris", rows.get(1)[1]);
    }

    // -------------------------------------------------------------------------
    // 2. TableInput — custom SELECT query with WHERE
    // -------------------------------------------------------------------------

    @Test
    void tableInputCustomQuery() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "ORDERS", "id", "status");
            insertRow(conn, "ORDERS", "1", "ACTIVE");
            insertRow(conn, "ORDERS", "2", "CLOSED");
            insertRow(conn, "ORDERS", "3", "ACTIVE");
        }

        Path output = tmp.resolve("query_out.csv");
        Map<String, String> params = jdbcParams(url);
        params.put("query", "SELECT * FROM ORDERS WHERE status='ACTIVE'");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "tableQuery";
        def.steps = List.of(
            step("src", "TableInput",     params),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> "ACTIVE".equals(r[1])));
    }

    // -------------------------------------------------------------------------
    // 3. TableInput — empty table returns zero rows without NPE
    // -------------------------------------------------------------------------

    @Test
    void tableInputEmptyResult() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "EMPTY_TBL", "col1");
        }

        Path output = tmp.resolve("empty_out.csv");
        Map<String, String> params = jdbcParams(url);
        params.put("tableName", "EMPTY_TBL");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "emptyTable";
        def.steps = List.of(
            step("src", "TableInput",     params),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        assertDoesNotThrow(() ->
            new TransformationExecutor(StepRegistry.withDefaults()).execute(def));

        // Output file may not exist or may be empty
        assertTrue(!Files.exists(output) || Files.size(output) == 0
                   || Files.readAllLines(output).isEmpty());
    }

    // -------------------------------------------------------------------------
    // 4. TableOutput — inserts rows via CsvInput → TableOutput
    // -------------------------------------------------------------------------

    @Test
    void tableOutputInsertsRows() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "DEST", "name", "score");
        }

        Path input = writeCsv("data.csv", "Alice,90", "Bob,85", "Carol,92",
                "Dave,78", "Eve,88");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName", "DEST");
        params.put("batchSize", "2");  // small batch to exercise mid-stream flush

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "tableOutput";
        def.steps = List.of(
            step("src", "CsvInput",    Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("dst", "TableOutput", params)
        );
        def.hops = List.of(hop("src", "dst"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "DEST");
            assertEquals(5, rows.size());
            assertEquals("Alice", rows.get(0)[0]);
        }
    }

    // -------------------------------------------------------------------------
    // 5. TableOutput — truncateFirst clears existing rows before inserting
    // -------------------------------------------------------------------------

    @Test
    void tableOutputTruncateFirst() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "TBL", "val");
            insertRow(conn, "TBL", "old1");
            insertRow(conn, "TBL", "old2");
        }

        Path input = writeCsv("new.csv", "new1", "new2");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName",    "TBL");
        params.put("truncateFirst", "true");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "truncate";
        def.steps = List.of(
            step("src", "CsvInput",    Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("dst", "TableOutput", params)
        );
        def.hops = List.of(hop("src", "dst"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "TBL");
            assertEquals(2, rows.size());
            assertEquals("new1", rows.get(0)[0]);
            assertEquals("new2", rows.get(1)[0]);
        }
    }

    // -------------------------------------------------------------------------
    // 6. TableOutput — last partial batch is flushed in close()
    // -------------------------------------------------------------------------

    @Test
    void tableOutputBatchFlushOnClose() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "BATCH_TBL", "n");
        }

        // batchSize=3, insert 4 rows → one flush mid-stream + one flush in close()
        Path input = writeCsv("batch.csv", "1", "2", "3", "4");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName", "BATCH_TBL");
        params.put("batchSize", "3");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "batchFlush";
        def.steps = List.of(
            step("src", "CsvInput",    Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("dst", "TableOutput", params)
        );
        def.hops = List.of(hop("src", "dst"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "BATCH_TBL");
            assertEquals(4, rows.size());
        }
    }

    // -------------------------------------------------------------------------
    // 7. InsertUpdate — inserts a new row when key not found
    // -------------------------------------------------------------------------

    @Test
    void insertUpdateStep_insertsNewRow() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "UPSERT", "id", "name");
        }

        Path input = writeCsv("upsert_in.csv", "1,Alice");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName",    "UPSERT");
        params.put("columns",      "id,name");
        params.put("keyFields",    "id");
        params.put("updateFields", "name");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "insertNew";
        def.steps = List.of(
            step("src", "CsvInput",     Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("dst", "InsertUpdate", params)
        );
        def.hops = List.of(hop("src", "dst"));

        // InsertUpdate is a streaming step — needs a sink to drain it
        // We route through a TextFileOutput to consume the pass-through
        Path sinkFile = tmp.resolve("iuout.csv");
        def.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("iupd", "InsertUpdate",  params),
            step("out",  "TextFileOutput", Map.of("filePath", sinkFile.toString()))
        );
        def.hops = List.of(hop("src", "iupd"), hop("iupd", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "UPSERT");
            assertEquals(1, rows.size());
            assertEquals("1",     rows.get(0)[0]);
            assertEquals("Alice", rows.get(0)[1]);
        }

        // Row passes through unchanged
        List<String[]> passThrough = readCsv(sinkFile);
        assertEquals(1, passThrough.size());
    }

    // -------------------------------------------------------------------------
    // 8. InsertUpdate — updates existing row when key matches
    // -------------------------------------------------------------------------

    @Test
    void insertUpdateStep_updatesExistingRow() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "UPSERT2", "id", "name");
            insertRow(conn, "UPSERT2", "1", "Alice");
        }

        Path input = writeCsv("upd_in.csv", "1,AliceUpdated");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName",    "UPSERT2");
        params.put("columns",      "id,name");
        params.put("keyFields",    "id");
        params.put("updateFields", "name");

        Path sinkFile = tmp.resolve("upd_out.csv");
        TransformationDefinition def = new TransformationDefinition();
        def.name  = "updateExisting";
        def.steps = List.of(
            step("src",  "CsvInput",      Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("iupd", "InsertUpdate",  params),
            step("out",  "TextFileOutput", Map.of("filePath", sinkFile.toString()))
        );
        def.hops = List.of(hop("src", "iupd"), hop("iupd", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "UPSERT2");
            assertEquals(1, rows.size());  // no duplicate inserted
            assertEquals("AliceUpdated", rows.get(0)[1]);
        }
    }

    // -------------------------------------------------------------------------
    // 9. Delete — deletes matching row and passes row through
    // -------------------------------------------------------------------------

    @Test
    void deleteStep_deletesMatchingRow() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "DELTBL", "id", "val");
            insertRow(conn, "DELTBL", "1", "keep");
            insertRow(conn, "DELTBL", "2", "delete");
            insertRow(conn, "DELTBL", "3", "keep");
        }

        // Delete the row with id=2
        Path input    = writeCsv("del_in.csv", "2,delete");
        Path sinkFile = tmp.resolve("del_out.csv");

        Map<String, String> params = jdbcParams(url);
        params.put("tableName", "DELTBL");
        params.put("columns",   "id,val");
        params.put("keyFields", "id");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "deleteRow";
        def.steps = List.of(
            step("src", "CsvInput",      Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("del", "Delete",         params),
            step("out", "TextFileOutput", Map.of("filePath", sinkFile.toString()))
        );
        def.hops = List.of(hop("src", "del"), hop("del", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "DELTBL");
            assertEquals(2, rows.size());
            assertTrue(rows.stream().noneMatch(r -> "2".equals(r[0])));
        }

        // The input row passes through to the sink
        List<String[]> passThrough = readCsv(sinkFile);
        assertEquals(1, passThrough.size());
        assertEquals("2", passThrough.get(0)[0]);
    }

    // -------------------------------------------------------------------------
    // 10. ExecSQL — executes parameterized UPDATE per row
    // -------------------------------------------------------------------------

    @Test
    void execSqlStep_executesParameterizedSql() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "EXSQL", "id", "status");
            insertRow(conn, "EXSQL", "1", "PENDING");
            insertRow(conn, "EXSQL", "2", "PENDING");
        }

        // Each input row: [id, newStatus] — UPDATE row by id
        Path input    = writeCsv("sql_in.csv", "1,DONE", "2,DONE");
        Path sinkFile = tmp.resolve("sql_out.csv");

        Map<String, String> params = jdbcParams(url);
        params.put("sql",         "UPDATE EXSQL SET status=? WHERE id=?");
        params.put("paramFields", "1,0"); // field[1]=newStatus, field[0]=id

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "execSql";
        def.steps = List.of(
            step("src", "CsvInput",      Map.of("filePath", input.toString(), "hasHeader", "false")),
            step("sql", "ExecSQL",        params),
            step("out", "TextFileOutput", Map.of("filePath", sinkFile.toString()))
        );
        def.hops = List.of(hop("src", "sql"), hop("sql", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        try (Connection conn = openConn(url)) {
            List<String[]> rows = queryAll(conn, "EXSQL");
            assertEquals(2, rows.size());
            assertTrue(rows.stream().allMatch(r -> "DONE".equals(r[1])));
        }

        // Rows pass through
        List<String[]> passThrough = readCsv(sinkFile);
        assertEquals(2, passThrough.size());
    }

    // -------------------------------------------------------------------------
    // 11. TableInput — dbType=h2 shorthand (no explicit jdbcDriver)
    // -------------------------------------------------------------------------

    @Test
    void tableInput_dbTypeShorthand_opensConnection() throws Exception {
        String url = freshDbUrl();
        try (Connection conn = openConn(url)) {
            conn.setAutoCommit(false);
            createTable(conn, "SHORTHAND", "val");
            insertRow(conn, "SHORTHAND", "hello");
        }

        Path output = tmp.resolve("shorthand_out.csv");

        // Use dbType=h2 instead of jdbcDriver=org.h2.Driver
        Map<String, String> params = new HashMap<>();
        params.put("dbType",       "h2");
        params.put("jdbcUrl",      url);
        params.put("jdbcUser",     "sa");
        params.put("jdbcPassword", "");
        params.put("tableName",    "SHORTHAND");

        TransformationDefinition def = new TransformationDefinition();
        def.name  = "dbTypeShorthand";
        def.steps = List.of(
            step("src", "TableInput",     params),
            step("out", "TextFileOutput", Map.of("filePath", output.toString()))
        );
        def.hops = List.of(hop("src", "out"));

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);

        List<String[]> rows = readCsv(output);
        assertEquals(1, rows.size());
        assertEquals("hello", rows.get(0)[0]);
    }
}
