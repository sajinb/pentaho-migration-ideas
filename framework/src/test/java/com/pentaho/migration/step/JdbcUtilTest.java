package com.pentaho.migration.step;

import com.pentaho.migration.step.impl.JdbcUtil;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JdbcUtil} driver resolution and URL construction.
 * These tests do NOT open a database connection.
 */
class JdbcUtilTest {

    // -------------------------------------------------------------------------
    // resolveDriver
    // -------------------------------------------------------------------------

    @Test
    void resolveDriver_explicitJdbcDriver_returnedAsIs() {
        Map<String, String> p = Map.of("jdbcDriver", "oracle.jdbc.OracleDriver");
        assertEquals("oracle.jdbc.OracleDriver", JdbcUtil.resolveDriver(p));
    }

    @Test
    void resolveDriver_dbTypeOracle_resolvesCorrectly() {
        assertEquals("oracle.jdbc.OracleDriver",
                JdbcUtil.resolveDriver(Map.of("dbType", "oracle")));
    }

    @Test
    void resolveDriver_dbTypeOracle_caseInsensitive() {
        assertEquals("oracle.jdbc.OracleDriver",
                JdbcUtil.resolveDriver(Map.of("dbType", "ORACLE")));
    }

    @Test
    void resolveDriver_dbTypeMySQL() {
        assertEquals("com.mysql.cj.jdbc.Driver",
                JdbcUtil.resolveDriver(Map.of("dbType", "mysql")));
    }

    @Test
    void resolveDriver_dbTypePostgreSQL() {
        assertEquals("org.postgresql.Driver",
                JdbcUtil.resolveDriver(Map.of("dbType", "postgresql")));
        assertEquals("org.postgresql.Driver",
                JdbcUtil.resolveDriver(Map.of("dbType", "postgres")));
    }

    @Test
    void resolveDriver_dbTypeSQLServer() {
        assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver",
                JdbcUtil.resolveDriver(Map.of("dbType", "sqlserver")));
        assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver",
                JdbcUtil.resolveDriver(Map.of("dbType", "mssql")));
    }

    @Test
    void resolveDriver_dbTypeH2() {
        assertEquals("org.h2.Driver",
                JdbcUtil.resolveDriver(Map.of("dbType", "h2")));
    }

    @Test
    void resolveDriver_explicitDriverTakesPrecedenceOverDbType() {
        Map<String, String> p = Map.of(
                "jdbcDriver", "com.custom.Driver",
                "dbType",     "oracle");
        assertEquals("com.custom.Driver", JdbcUtil.resolveDriver(p));
    }

    @Test
    void resolveDriver_neitherSet_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUtil.resolveDriver(Map.of()));
    }

    @Test
    void resolveDriver_unknownDbType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUtil.resolveDriver(Map.of("dbType", "teradata")));
    }

    // -------------------------------------------------------------------------
    // resolveUrl — explicit jdbcUrl always wins
    // -------------------------------------------------------------------------

    @Test
    void resolveUrl_explicitJdbcUrl_returnedAsIs() {
        String url = "jdbc:oracle:thin:@myhost:1521:PROD";
        assertEquals(url,
                JdbcUtil.resolveUrl(Map.of("jdbcUrl", url)));
    }

    // -------------------------------------------------------------------------
    // buildUrl — Oracle SID format
    // -------------------------------------------------------------------------

    @Test
    void buildOracleUrl_sid_defaultPort() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",   "oracle");
        p.put("jdbcHost", "dbserver");
        p.put("jdbcSid",  "PROD");
        assertEquals("jdbc:oracle:thin:@dbserver:1521:PROD", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildOracleUrl_sid_customPort() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",   "oracle");
        p.put("jdbcHost", "dbserver");
        p.put("jdbcPort", "1522");
        p.put("jdbcSid",  "ORCL");
        assertEquals("jdbc:oracle:thin:@dbserver:1522:ORCL", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildOracleUrl_sid_defaultsToORCLWhenNeitherSidNorServiceName() {
        Map<String, String> p = Map.of(
                "dbType",   "oracle",
                "jdbcHost", "localhost");
        assertEquals("jdbc:oracle:thin:@localhost:1521:ORCL", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildOracleUrl_sid_jdbcDatabaseFallback() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",       "oracle");
        p.put("jdbcHost",     "prod-db");
        p.put("jdbcDatabase", "MYDB");
        assertEquals("jdbc:oracle:thin:@prod-db:1521:MYDB", JdbcUtil.buildUrl(p));
    }

    // -------------------------------------------------------------------------
    // buildUrl — Oracle service-name format
    // -------------------------------------------------------------------------

    @Test
    void buildOracleUrl_serviceName_defaultPort() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",          "oracle");
        p.put("jdbcHost",        "cloud-db");
        p.put("jdbcServiceName", "myapp.region.oracle.com");
        assertEquals("jdbc:oracle:thin:@//cloud-db:1521/myapp.region.oracle.com",
                JdbcUtil.buildUrl(p));
    }

    @Test
    void buildOracleUrl_serviceName_customPort() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",          "oracle");
        p.put("jdbcHost",        "cloud-db");
        p.put("jdbcPort",        "1522");
        p.put("jdbcServiceName", "myapp.db");
        assertEquals("jdbc:oracle:thin:@//cloud-db:1522/myapp.db", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildOracleUrl_serviceName_takesPrecedenceOverSid() {
        Map<String, String> p = new HashMap<>();
        p.put("dbType",          "oracle");
        p.put("jdbcHost",        "host");
        p.put("jdbcSid",         "OLDSID");
        p.put("jdbcServiceName", "newsvc");
        // Service name wins
        assertTrue(JdbcUtil.buildUrl(p).contains("//host:1521/newsvc"));
    }

    // -------------------------------------------------------------------------
    // buildUrl — other databases
    // -------------------------------------------------------------------------

    @Test
    void buildUrl_mysql() {
        Map<String, String> p = Map.of(
                "dbType", "mysql", "jdbcHost", "localhost",
                "jdbcPort", "3306", "jdbcDatabase", "shop");
        assertEquals("jdbc:mysql://localhost:3306/shop", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildUrl_postgresql() {
        Map<String, String> p = Map.of(
                "dbType", "postgresql", "jdbcHost", "pghost",
                "jdbcDatabase", "mydb");
        assertEquals("jdbc:postgresql://pghost:5432/mydb", JdbcUtil.buildUrl(p));
    }

    @Test
    void buildUrl_sqlserver() {
        Map<String, String> p = Map.of(
                "dbType", "sqlserver", "jdbcHost", "sqlhost",
                "jdbcPort", "1433", "jdbcDatabase", "AdventureWorks");
        assertEquals("jdbc:sqlserver://sqlhost:1433;databaseName=AdventureWorks",
                JdbcUtil.buildUrl(p));
    }

    @Test
    void buildUrl_unknownDbType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUtil.buildUrl(Map.of("dbType", "informix")));
    }
}
