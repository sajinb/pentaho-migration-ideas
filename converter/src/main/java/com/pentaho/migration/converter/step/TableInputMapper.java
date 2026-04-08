package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code TableInput} XML to our {@code TableInput} step params.
 *
 * <p>Pentaho stores JDBC settings inside a {@code <connection>} child element
 * (or as a reference to a named connection defined at the transformation level).
 * This mapper extracts inline connection details when present; named connections
 * must be resolved by the caller ({@link com.pentaho.migration.converter.KtrParser}).
 *
 * <p>The emitted params are compatible with both the legacy {@code jdbcDriver} +
 * {@code jdbcUrl} style and the new {@code dbType} shorthand supported by
 * {@link com.pentaho.migration.step.impl.JdbcUtil}.
 */
public final class TableInputMapper implements StepXmlMapper {

    /**
     * Maps Pentaho connection type names (from {@code <type>}) to the {@code dbType}
     * shorthand understood by {@code JdbcUtil}.
     */
    private static final Map<String, String> DB_TYPE_MAP = Map.of(
            "ORACLE",      "oracle",
            "MYSQL",       "mysql",
            "POSTGRESQL",  "postgresql",
            "MSSQLNATIVE", "sqlserver",
            "MSSQL",       "sqlserver",
            "H2",          "h2",
            "DB2",         "db2"
    );

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Inline <connection> block (some exports embed the full connection here)
        NodeList connNodes = e.getElementsByTagName("connection");
        if (connNodes.getLength() > 0) {
            Element conn = (Element) connNodes.item(0);
            mapConnection(conn, p);
        }

        // Raw SQL query
        put(p, "query", child(e, "sql"));

        return p;
    }

    /**
     * Extracts JDBC params from a Pentaho {@code <connection>} element.
     *
     * <p>Priority for driver/URL resolution:
     * <ol>
     *   <li>If {@code <driver>} is set explicitly, emit as {@code jdbcDriver}
     *   <li>Otherwise emit {@code dbType} derived from {@code <type>} — JdbcUtil resolves the driver
     * </ol>
     *
     * <p>Priority for URL:
     * <ol>
     *   <li>If {@code <jdbcUrl>} is set explicitly, use it
     *   <li>Otherwise emit individual params ({@code jdbcHost}, {@code jdbcPort}, etc.)
     *       and let JdbcUtil build the URL
     * </ol>
     */
    private static void mapConnection(Element conn, Map<String, String> p) {
        // --- Driver ---
        String explicitDriver = child(conn, "driver");
        if (explicitDriver != null && !explicitDriver.isBlank()) {
            p.put("jdbcDriver", explicitDriver);
        } else {
            String pentahoType = child(conn, "type", "").toUpperCase();
            String dbType = DB_TYPE_MAP.get(pentahoType);
            if (dbType != null) {
                p.put("dbType", dbType);
            }
        }

        // --- URL ---
        String explicitUrl = child(conn, "jdbcUrl");
        if (explicitUrl != null && !explicitUrl.isBlank()) {
            p.put("jdbcUrl", explicitUrl);
        } else {
            // Emit individual params so JdbcUtil can build the URL
            put(p, "jdbcHost", child(conn, "server"));
            put(p, "jdbcPort", child(conn, "port"));

            String pentahoType = child(conn, "type", "").toUpperCase();
            String dbName = child(conn, "database");
            if ("ORACLE".equals(pentahoType) && dbName != null) {
                // Pentaho stores the Oracle SID (or service name) in <database>
                // Emit as jdbcSid; users can change to jdbcServiceName if needed
                p.put("jdbcSid", dbName);
            } else {
                put(p, "jdbcDatabase", dbName);
            }
        }

        // --- Credentials ---
        put(p, "jdbcUser",     child(conn, "username"));
        put(p, "jdbcPassword", child(conn, "password"));
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
