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
 */
public final class TableInputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Inline <connection> block (some exports embed the full connection here)
        NodeList connNodes = e.getElementsByTagName("connection");
        if (connNodes.getLength() > 0) {
            Element conn = (Element) connNodes.item(0);
            put(p, "jdbcUrl",      buildUrl(conn));
            put(p, "jdbcDriver",   child(conn, "driver"));
            put(p, "jdbcUser",     child(conn, "username"));
            put(p, "jdbcPassword", child(conn, "password"));
        }

        // Raw SQL query
        put(p, "query", child(e, "sql"));

        return p;
    }

    /** Builds a JDBC URL from Pentaho connection XML when the full URL is not explicit. */
    private static String buildUrl(Element conn) {
        String url = child(conn, "jdbcUrl");
        if (url != null) return url;
        // Pentaho stores host/port/dbname — try to assemble a generic URL
        String type = child(conn, "type", "");
        String host = child(conn, "server", "localhost");
        String port = child(conn, "port", "");
        String db   = child(conn, "database", "");
        return switch (type.toUpperCase()) {
            case "MYSQL"      -> "jdbc:mysql://" + host + (port.isEmpty() ? "" : ":" + port) + "/" + db;
            case "POSTGRESQL" -> "jdbc:postgresql://" + host + (port.isEmpty() ? "" : ":" + port) + "/" + db;
            case "ORACLE"     -> "jdbc:oracle:thin:@" + host + ":" + (port.isEmpty() ? "1521" : port) + ":" + db;
            case "MSSQLNATIVE"-> "jdbc:sqlserver://" + host + (port.isEmpty() ? "" : ":" + port) + ";databaseName=" + db;
            default           -> null;
        };
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
