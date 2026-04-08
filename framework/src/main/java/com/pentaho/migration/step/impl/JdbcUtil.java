package com.pentaho.migration.step.impl;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;

/**
 * Shared JDBC helper: opens a connection from a params map.
 *
 * <h3>Required params (one of)</h3>
 * <ul>
 *   <li>{@code jdbcDriver} — fully-qualified driver class (e.g. {@code oracle.jdbc.OracleDriver})
 *   <li>{@code dbType}     — shorthand resolved to a known driver class (see below)
 * </ul>
 *
 * <h3>Connection URL (one of)</h3>
 * <ul>
 *   <li>{@code jdbcUrl} — full JDBC URL (takes precedence)
 *   <li>Or individual params for URL construction: {@code jdbcHost}, {@code jdbcPort},
 *       plus one of {@code jdbcDatabase} / {@code jdbcSid} / {@code jdbcServiceName}
 * </ul>
 *
 * <h3>Supported {@code dbType} values</h3>
 * <table>
 *   <tr><th>dbType</th><th>Driver class</th><th>Default port</th></tr>
 *   <tr><td>oracle</td><td>oracle.jdbc.OracleDriver</td><td>1521</td></tr>
 *   <tr><td>mysql</td><td>com.mysql.cj.jdbc.Driver</td><td>3306</td></tr>
 *   <tr><td>postgresql / postgres</td><td>org.postgresql.Driver</td><td>5432</td></tr>
 *   <tr><td>sqlserver / mssql</td><td>com.microsoft.sqlserver.jdbc.SQLServerDriver</td><td>1433</td></tr>
 *   <tr><td>h2</td><td>org.h2.Driver</td><td>—</td></tr>
 *   <tr><td>db2</td><td>com.ibm.db2.jcc.DB2Driver</td><td>50000</td></tr>
 * </table>
 *
 * <h3>Oracle URL construction</h3>
 * When {@code jdbcUrl} is absent and {@code dbType=oracle}:
 * <ul>
 *   <li>{@code jdbcServiceName} set → service-name format:
 *       {@code jdbc:oracle:thin:@//host:port/serviceName}
 *   <li>{@code jdbcSid} (or {@code jdbcDatabase}) set → SID format:
 *       {@code jdbc:oracle:thin:@host:port:SID} (default SID: {@code ORCL})
 * </ul>
 *
 * <p>The returned connection has {@code autoCommit=false}; callers must commit or roll back.
 */
public final class JdbcUtil {

    /** Maps {@code dbType} shorthand → fully-qualified JDBC driver class name. */
    private static final Map<String, String> DRIVER_DEFAULTS = Map.of(
            "oracle",     "oracle.jdbc.OracleDriver",
            "mysql",      "com.mysql.cj.jdbc.Driver",
            "postgresql", "org.postgresql.Driver",
            "postgres",   "org.postgresql.Driver",
            "sqlserver",  "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "mssql",      "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "h2",         "org.h2.Driver",
            "db2",        "com.ibm.db2.jcc.DB2Driver"
    );

    /** Default JDBC port per {@code dbType}. */
    private static final Map<String, String> DEFAULT_PORTS = Map.of(
            "oracle",     "1521",
            "mysql",      "3306",
            "postgresql", "5432",
            "postgres",   "5432",
            "sqlserver",  "1433",
            "mssql",      "1433",
            "db2",        "50000"
    );

    private JdbcUtil() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Opens a JDBC connection from the given params map (autoCommit=false). */
    public static Connection openConnection(Map<String, String> params) throws SQLException {
        String driver = resolveDriver(params);
        String url    = resolveUrl(params);
        String user   = params.getOrDefault("jdbcUser", "");
        String pass   = params.getOrDefault("jdbcPassword", "");

        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            throw new SQLException("JDBC driver not found on classpath: " + driver, e);
        }

        Connection conn = DriverManager.getConnection(url, user, pass);
        conn.setAutoCommit(false);
        return conn;
    }

    /**
     * Resolves the JDBC driver class name from params.
     * Priority: explicit {@code jdbcDriver} param → {@code dbType} shorthand.
     *
     * @throws IllegalArgumentException if neither param is set or {@code dbType} is unknown
     */
    public static String resolveDriver(Map<String, String> params) {
        String explicit = params.get("jdbcDriver");
        if (explicit != null && !explicit.isBlank()) return explicit;

        String dbType = params.get("dbType");
        if (dbType != null) {
            String driver = DRIVER_DEFAULTS.get(dbType.toLowerCase());
            if (driver != null) return driver;
            throw new IllegalArgumentException(
                    "Unknown dbType '" + dbType + "'. Supported: " +
                    String.join(", ", DRIVER_DEFAULTS.keySet()));
        }
        throw new IllegalArgumentException(
                "No JDBC driver specified. Set 'jdbcDriver' (fully-qualified class name) " +
                "or 'dbType' (oracle, mysql, postgresql, sqlserver, h2, db2).");
    }

    /**
     * Resolves the JDBC URL from params.
     * Priority: explicit {@code jdbcUrl} param → URL built from individual params.
     *
     * @throws IllegalArgumentException if URL cannot be determined
     */
    public static String resolveUrl(Map<String, String> params) {
        String url = params.get("jdbcUrl");
        if (url != null && !url.isBlank()) return url;
        return buildUrl(params);
    }

    // -------------------------------------------------------------------------
    // URL construction
    // -------------------------------------------------------------------------

    /**
     * Constructs a JDBC URL from individual connection parameters.
     * Requires {@code dbType} to be set.
     *
     * <p>Oracle-specific params:
     * <ul>
     *   <li>{@code jdbcServiceName} — Oracle service name (preferred, uses {@code //host:port/name} syntax)
     *   <li>{@code jdbcSid}         — Oracle SID (legacy, uses {@code host:port:sid} syntax)
     *   <li>{@code jdbcDatabase}    — falls back to SID when neither of the above is set
     * </ul>
     */
    public static String buildUrl(Map<String, String> params) {
        String dbType = params.getOrDefault("dbType", "").toLowerCase();
        String host   = params.getOrDefault("jdbcHost", "localhost");
        String port   = params.getOrDefault("jdbcPort", DEFAULT_PORTS.getOrDefault(dbType, ""));
        String db     = params.getOrDefault("jdbcDatabase", "");

        return switch (dbType) {
            case "oracle"               -> buildOracleUrl(params, host, port);
            case "mysql"                -> "jdbc:mysql://" + host + portSuffix(port) + "/" + db;
            case "postgresql", "postgres"
                                        -> "jdbc:postgresql://" + host + portSuffix(port) + "/" + db;
            case "sqlserver", "mssql"   -> "jdbc:sqlserver://" + host + portSuffix(port)
                                             + ";databaseName=" + db;
            case "h2"                   -> db.isEmpty()
                                             ? "jdbc:h2:mem:test"
                                             : "jdbc:h2:mem:" + db;
            case "db2"                  -> "jdbc:db2://" + host + portSuffix(port) + "/" + db;
            default                     -> throw new IllegalArgumentException(
                    "Cannot build JDBC URL: set 'jdbcUrl' explicitly, " +
                    "or set 'dbType' to one of: oracle, mysql, postgresql, sqlserver, h2, db2.");
        };
    }

    /**
     * Builds an Oracle JDBC URL.
     *
     * <ul>
     *   <li>If {@code jdbcServiceName} is set → {@code jdbc:oracle:thin:@//host:port/serviceName}
     *   <li>Otherwise → {@code jdbc:oracle:thin:@host:port:SID}
     *       (SID comes from {@code jdbcSid} → {@code jdbcDatabase} → default {@code ORCL})
     * </ul>
     */
    public static String buildOracleUrl(Map<String, String> params, String host, String port) {
        String serviceName = params.get("jdbcServiceName");
        if (serviceName != null && !serviceName.isBlank()) {
            // Thin driver service-name format
            return "jdbc:oracle:thin:@//" + host + portSuffix(port) + "/" + serviceName;
        }
        // SID format (legacy but widely used)
        String sid = params.containsKey("jdbcSid")
                ? params.get("jdbcSid")
                : params.getOrDefault("jdbcDatabase", "ORCL");
        return "jdbc:oracle:thin:@" + host + ":" + port + ":" + sid;
    }

    private static String portSuffix(String port) {
        return (port == null || port.isBlank()) ? "" : ":" + port;
    }
}
