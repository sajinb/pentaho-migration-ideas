package com.pentaho.migration.converter;

import com.pentaho.migration.model.EntryDefinition;
import com.pentaho.migration.model.EntryHopDefinition;
import com.pentaho.migration.model.JobDefinition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Pentaho KJB (job) XML document into a {@link JobDefinition}.
 *
 * <p>Handles the standard Pentaho 8.x / 9.x KJB XML schema:
 * <pre>
 * &lt;job&gt;
 *   &lt;name&gt;…&lt;/name&gt;
 *   &lt;entries&gt;&lt;entry&gt;…&lt;/entry&gt;&lt;/entries&gt;
 *   &lt;hops&gt;&lt;hop&gt;…&lt;/hop&gt;&lt;/hops&gt;
 * &lt;/job&gt;
 * </pre>
 */
public final class KjbParser {

    /**
     * Parses a KJB XML stream into a {@link JobDefinition}.
     *
     * @param kjbXml input stream of a {@code .kjb} file
     * @return parsed job definition
     */
    public JobDefinition parse(InputStream kjbXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(kjbXml);
        doc.getDocumentElement().normalize();

        JobDefinition def = new JobDefinition();
        def.name       = extractName(doc);
        def.parameters = extractParameters(doc);
        def.entries    = extractEntries(doc);
        def.hops       = extractHops(doc);
        return def;
    }

    // -------------------------------------------------------------------------

    private String extractName(Document doc) {
        NodeList names = doc.getDocumentElement().getElementsByTagName("name");
        // The job's own <name> is a direct child of <job>
        for (int i = 0; i < names.getLength(); i++) {
            if (names.item(i).getParentNode().equals(doc.getDocumentElement())) {
                return names.item(i).getTextContent().trim();
            }
        }
        return "unnamed";
    }

    private Map<String, String> extractParameters(Document doc) {
        Map<String, String> params = new HashMap<>();
        // Parameters are in <job><parameters><parameter><name> / <default_value>
        NodeList paramNodes = doc.getElementsByTagName("parameter");
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element el = (Element) paramNodes.item(i);
            String name         = text(el, "name");
            String defaultValue = text(el, "default_value");
            if (name != null && !name.isBlank()) {
                params.put(name, defaultValue != null ? defaultValue : "");
            }
        }
        return params;
    }

    private List<EntryDefinition> extractEntries(Document doc) {
        List<EntryDefinition> entries = new ArrayList<>();
        // Real KJBs use <jobentry>; synthetic/test KJBs may use <entry>
        NodeList entryNodes = doc.getElementsByTagName("jobentry");
        if (entryNodes.getLength() == 0) {
            entryNodes = doc.getElementsByTagName("entry");
        }
        for (int i = 0; i < entryNodes.getLength(); i++) {
            Element el = (Element) entryNodes.item(i);

            EntryDefinition ed = new EntryDefinition();
            ed.id     = text(el, "name");
            ed.type   = resolveEntryType(el);
            ed.params = buildEntryParams(el, ed.type);
            entries.add(ed);
        }
        return entries;
    }

    /**
     * Resolves the entry type from the {@code <type>} element.
     *
     * <p>Pentaho uses {@code SPECIAL} for Start/Success/Abort entries,
     * differentiated by {@code <start>}, {@code <success>}, {@code <abort>} child elements.
     */
    private static String resolveEntryType(Element el) {
        String type = text(el, "type");
        if ("SPECIAL".equalsIgnoreCase(type)) {
            if ("Y".equalsIgnoreCase(text(el, "start")))   return "Start";
            if ("Y".equalsIgnoreCase(text(el, "success"))) return "Success";
            if ("Y".equalsIgnoreCase(text(el, "abort")))   return "Abort";
            if ("Y".equalsIgnoreCase(text(el, "dummy")))   return "Dummy";
            // Real Pentaho KJBs sometimes omit <success>Y</success> for SUCCESS endpoints.
            // A SPECIAL entry with no positive flag is always a Success terminal.
            return "Success";
        }
        return normalizeEntryType(type);
    }

    private static String normalizeEntryType(String type) {
        if (type == null) return "Unknown";
        return switch (type.toUpperCase()) {
            case "TRANS", "TRANSFORMATION" -> "RunTransformation";
            case "JOB"                -> "RunJob";
            case "MAIL"               -> "Mail";
            case "WRITE_TO_LOG"       -> "WriteToLog";
            case "EXEC_SQL"           -> "ExecSQL";
            case "SET_VARIABLE"       -> "SetVariable";
            case "GET_VARIABLE"       -> "GetVariable";
            case "EXEC_PROCESS"       -> "ExecProcess";
            // Both standard FILE_EXISTS and Pentaho's CHECK_FILE_EXISTS map to FileExists
            case "FILE_EXISTS", "CHECK_FILE_EXISTS" -> "FileExists";
            // Shell scripts run via /bin/sh — map to ExecProcess which already handles that
            case "SHELL"              -> "ExecProcess";
            // Explicit Pentaho sync barrier. Our engine's CompletableFuture.allOf() fan-in
            // provides identical semantics from hop topology alone, so this entry is a no-op.
            case "BLOCKUNTILSTEPSFINISH" -> "Dummy";
            case "DUMMY"              -> "Dummy";
            // File-system / evaluation job entries — no native implementations yet.
            // Mapped to Dummy (always returns true) so the engine can parse and run
            // the rest of the job; callers should replace with real implementations.
            case "EVAL_FILES_METRICS" -> "Dummy";   // check file size/count condition
            case "DELETE_FILE"        -> "Dummy";   // delete a file
            case "EVAL"               -> "Dummy";   // evaluate JavaScript/expression condition
            case "MOVE_FILES"         -> "Dummy";   // move or rename files
            // These three can appear either as SPECIAL sub-types (handled in resolveEntryType)
            // or directly as top-level type values in non-standard KJBs.
            case "SUCCESS"            -> "Success";
            case "ABORT"              -> "Abort";
            case "START"              -> "Start";
            default                   -> type;
        };
    }

    private static Map<String, String> buildEntryParams(Element el, String resolvedType) {
        Map<String, String> params = new HashMap<>();
        switch (resolvedType) {
            case "RunTransformation" -> {
                // Real KJBs use <trans_filename>; older/synthetic KJBs use <filename>
                String filename = text(el, "trans_filename");
                if (filename == null) filename = text(el, "filename");
                if (filename != null) {
                    params.put("transformationPath", filename.replaceAll("\\.ktr$", ".yaml"));
                }
            }
            case "RunJob" -> {
                String filename = text(el, "filename");
                if (filename != null) {
                    params.put("jobPath", filename.replaceAll("\\.kjb$", ".yaml"));
                }
            }
            case "WriteToLog" -> {
                String msg = text(el, "logmessage");
                if (msg != null) params.put("message", msg);
            }
            case "ExecSQL" -> {
                String sql = text(el, "sql");
                if (sql != null) params.put("sql", sql);
            }
            case "ExecProcess" -> {
                // Standard ExecProcess uses <command>; Shell entry uses <script> CDATA
                String cmd = text(el, "command");
                if (cmd == null || cmd.isBlank()) cmd = text(el, "script");
                if (cmd != null) params.put("command", cmd);
            }
            case "FileExists" -> {
                // Both FILE_EXISTS and CHECK_FILE_EXISTS use <filename>
                String filePath = text(el, "filename");
                if (filePath != null) params.put("filePath", filePath);
                String failIfNo = text(el, "fail_if_no_file");
                if (failIfNo != null) params.put("failIfNoFile", "Y".equalsIgnoreCase(failIfNo) ? "true" : "false");
            }
            case "Mail" -> {
                // Recipient / subject / body
                String dest = text(el, "destination");
                if (dest != null) params.put("to", dest);
                String subj = text(el, "subject");
                if (subj != null) params.put("subject", subj);
                String body = text(el, "body");
                if (body != null) params.put("body", body);
                // SMTP server config (optional — runtime may have defaults)
                String server = text(el, "server");
                if (server != null) params.put("host", server);
                String from = text(el, "from_address");
                if (from == null) from = text(el, "replyto");
                if (from != null) params.put("from", from);
            }
            // Start, Success, Abort, Dummy — no params needed
        }
        return params;
    }

    private List<EntryHopDefinition> extractHops(Document doc) {
        List<EntryHopDefinition> hops = new ArrayList<>();
        // Real KJBs use <jobhop>; synthetic/test KJBs may use <hop>
        NodeList hopNodes = doc.getElementsByTagName("jobhop");
        if (hopNodes.getLength() == 0) {
            hopNodes = doc.getElementsByTagName("hop");
        }
        for (int i = 0; i < hopNodes.getLength(); i++) {
            Element el = (Element) hopNodes.item(i);
            // Skip disabled hops
            String enabled = text(el, "enabled");
            if ("N".equalsIgnoreCase(enabled)) continue;

            EntryHopDefinition hop = new EntryHopDefinition();
            hop.from       = text(el, "from");
            hop.to         = text(el, "to");
            hop.evaluation = resolveEvaluation(el);
            hops.add(hop);
        }
        return hops;
    }

    /**
     * Maps Pentaho hop evaluation flags to our {@code "success" | "failure" | "unconditional"}.
     *
     * <p>Pentaho uses:
     * <ul>
     *   <li>{@code <unconditional>Y</unconditional>} → unconditional</li>
     *   <li>{@code <evaluation>true</evaluation>} + unconditional=N → success</li>
     *   <li>{@code <evaluation>false</evaluation>} + unconditional=N → failure</li>
     * </ul>
     */
    private static String resolveEvaluation(Element hop) {
        String unconditional = text(hop, "unconditional");
        if ("Y".equalsIgnoreCase(unconditional) || "true".equalsIgnoreCase(unconditional)) {
            return "unconditional";
        }
        String evaluation = text(hop, "evaluation");
        // Real KJBs use Y/N; test/synthetic KJBs use true/false — handle both.
        if ("false".equalsIgnoreCase(evaluation) || "N".equalsIgnoreCase(evaluation)) return "failure";
        return "success"; // default: true / Y / missing
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }
}
