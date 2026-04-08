package com.pentaho.migration.converter;

import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Oracle (and other DB) parameter mapping in the KTR → YAML converter.
 * Verifies that {@code TableInputMapper} emits the correct {@code dbType}, URL, and
 * credential params from a Pentaho inline {@code <connection>} element.
 */
class TableInputMapperTest {

    private final KtrParser parser = new KtrParser();

    private TransformationDefinition parse(String ktr) throws Exception {
        return parser.parse(new ByteArrayInputStream(ktr.getBytes(StandardCharsets.UTF_8)));
    }

    private StepDefinition firstStep(String ktr) throws Exception {
        return parse(ktr).steps.get(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Wraps a <step> block in a minimal KTR document. */
    private static String ktr(String stepXml) {
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <transformation>
              <info><name>t</name></info>
              %s
              <order/>
            </transformation>
            """.formatted(stepXml);
    }

    // -------------------------------------------------------------------------
    // Oracle — inline connection, SID-based
    // -------------------------------------------------------------------------

    @Test
    void oracleConnection_sid_emitsDbTypeAndSid() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>ORACLE</type>
                <server>ora-prod-01</server>
                <port>1521</port>
                <database>MYDB</database>
                <username>appuser</username>
                <password>s3cr3t</password>
              </connection>
              <sql>SELECT * FROM ORDERS</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        assertEquals("TableInput", sd.type);
        // dbType shorthand — JdbcUtil resolves oracle.jdbc.OracleDriver from this
        assertEquals("oracle",      sd.params.get("dbType"));
        // SID stored in jdbcSid (not jdbcDatabase)
        assertEquals("MYDB",        sd.params.get("jdbcSid"));
        assertEquals("ora-prod-01", sd.params.get("jdbcHost"));
        assertEquals("1521",        sd.params.get("jdbcPort"));
        assertEquals("appuser",     sd.params.get("jdbcUser"));
        assertEquals("s3cr3t",      sd.params.get("jdbcPassword"));
        assertEquals("SELECT * FROM ORDERS", sd.params.get("query"));
        // Should NOT set jdbcUrl — that's built at runtime by JdbcUtil
        assertNull(sd.params.get("jdbcUrl"),
                "jdbcUrl should be absent; JdbcUtil builds it from host/port/sid");
    }

    // -------------------------------------------------------------------------
    // Oracle — explicit jdbcUrl in connection overrides host/port params
    // -------------------------------------------------------------------------

    @Test
    void oracleConnection_explicitJdbcUrl_usedDirectly() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>ORACLE</type>
                <jdbcUrl>jdbc:oracle:thin:@//cloud-host:1522/MYSERVICE</jdbcUrl>
                <username>svc</username>
                <password>pw</password>
              </connection>
              <sql>SELECT 1 FROM DUAL</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        assertEquals("jdbc:oracle:thin:@//cloud-host:1522/MYSERVICE",
                sd.params.get("jdbcUrl"));
        // dbType still set (useful for documentation / tooling)
        assertEquals("oracle", sd.params.get("dbType"));
    }

    // -------------------------------------------------------------------------
    // Oracle — explicit <driver> overrides dbType resolution
    // -------------------------------------------------------------------------

    @Test
    void oracleConnection_explicitDriver_usedInsteadOfDbType() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>ORACLE</type>
                <driver>oracle.jdbc.OracleDriver</driver>
                <server>host</server>
                <port>1521</port>
                <database>ORCL</database>
                <username>u</username>
                <password>p</password>
              </connection>
              <sql>SELECT 1 FROM DUAL</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        // Explicit driver takes precedence over dbType shorthand
        assertEquals("oracle.jdbc.OracleDriver", sd.params.get("jdbcDriver"));
        // dbType should NOT be emitted when jdbcDriver is explicit
        assertNull(sd.params.get("dbType"));
    }

    // -------------------------------------------------------------------------
    // MySQL connection
    // -------------------------------------------------------------------------

    @Test
    void mysqlConnection_emitsCorrectDbType() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>MYSQL</type>
                <server>mysql-host</server>
                <port>3306</port>
                <database>shop</database>
                <username>root</username>
                <password></password>
              </connection>
              <sql>SELECT * FROM products</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        assertEquals("mysql",      sd.params.get("dbType"));
        assertEquals("mysql-host", sd.params.get("jdbcHost"));
        assertEquals("3306",       sd.params.get("jdbcPort"));
        assertEquals("shop",       sd.params.get("jdbcDatabase"));
    }

    // -------------------------------------------------------------------------
    // PostgreSQL connection
    // -------------------------------------------------------------------------

    @Test
    void postgresqlConnection_emitsCorrectDbType() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>POSTGRESQL</type>
                <server>pg-host</server>
                <port>5432</port>
                <database>analytics</database>
                <username>analyst</username>
                <password>pw</password>
              </connection>
              <sql>SELECT * FROM events</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        assertEquals("postgresql", sd.params.get("dbType"));
        assertEquals("pg-host",    sd.params.get("jdbcHost"));
        assertEquals("analytics",  sd.params.get("jdbcDatabase"));
    }

    // -------------------------------------------------------------------------
    // No connection block — only SQL query
    // -------------------------------------------------------------------------

    @Test
    void noConnectionBlock_onlySqlEmitted() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <sql>SELECT * FROM T</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        assertEquals("SELECT * FROM T", sd.params.get("query"));
        assertNull(sd.params.get("dbType"));
        assertNull(sd.params.get("jdbcDriver"));
    }

    // -------------------------------------------------------------------------
    // Unknown DB type — no dbType emitted (caller must supply jdbcDriver + jdbcUrl)
    // -------------------------------------------------------------------------

    @Test
    void unknownConnectionType_noDbTypeEmitted() throws Exception {
        String ktr = ktr("""
            <step>
              <name>read</name>
              <type>TableInput</type>
              <connection>
                <type>TERADATA</type>
                <server>td-host</server>
                <database>mydb</database>
                <username>u</username>
                <password>p</password>
              </connection>
              <sql>SELECT 1</sql>
            </step>
            """);

        StepDefinition sd = firstStep(ktr);

        // Unknown types must be manually supplied at runtime
        assertNull(sd.params.get("dbType"),     "Unknown DB type should not set dbType");
        assertNull(sd.params.get("jdbcDriver"), "Unknown DB type should not set jdbcDriver");
    }
}
