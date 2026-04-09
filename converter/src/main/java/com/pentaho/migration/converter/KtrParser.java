package com.pentaho.migration.converter;

import com.pentaho.migration.converter.step.StepXmlMapper;
import com.pentaho.migration.converter.step.StepXmlMapperRegistry;
import com.pentaho.migration.model.HopDefinition;
import com.pentaho.migration.model.StepDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses a Pentaho KTR (transformation) XML document into a {@link TransformationDefinition}.
 *
 * <p>Handles the standard Pentaho 8.x / 9.x KTR XML schema:
 * <pre>
 * &lt;transformation&gt;
 *   &lt;info&gt;&lt;name&gt;…&lt;/name&gt;&lt;/info&gt;
 *   &lt;step&gt;…&lt;/step&gt; (repeated)
 *   &lt;order&gt;&lt;hop&gt;…&lt;/hop&gt;&lt;/order&gt;
 * &lt;/transformation&gt;
 * </pre>
 */
public final class KtrParser {

    /**
     * Pentaho type names that differ from our registry keys.
     * All other types are passed through unchanged (case preserved).
     */
    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("CSVInput",              "CsvInput"),
            Map.entry("CsvInput",              "CsvInput"),
            Map.entry("TextFileInput",         "TextFileInput"),
            Map.entry("TextFileOutput",        "TextFileOutput"),
            Map.entry("SortRows",              "SortRows"),
            Map.entry("FilterRows",            "FilterRows"),
            Map.entry("SelectValues",          "SelectValues"),
            Map.entry("Constant",              "Constant"),
            Map.entry("SetValueConstant",      "SetValueConstant"),
            Map.entry("SetValueField",         "SetValueField"),
            Map.entry("IfNull",                "IfNull"),
            Map.entry("NullIf",                "NullIf"),
            Map.entry("StringOperations",      "StringOperations"),
            Map.entry("ReplaceString",         "ReplaceString"),
            Map.entry("StringCut",             "StringCut"),
            Map.entry("ConcatFields",          "ConcatFields"),
            Map.entry("Calculator",            "Calculator"),
            Map.entry("Formula",               "Formula"),
            Map.entry("SetVariable",           "SetVariable"),
            Map.entry("GetVariable",           "GetVariable"),
            Map.entry("ValueMapper",           "ValueMapper"),
            Map.entry("NumberRange",           "NumberRange"),
            Map.entry("CheckSum",              "CheckSum"),
            Map.entry("RegexEval",             "RegexEval"),
            Map.entry("FieldSplitter",         "FieldSplitter"),
            Map.entry("CloneRow",              "CloneRow"),
            Map.entry("Sequence",              "Sequence"),
            Map.entry("Dummy (do nothing)",    "Dummy"),
            Map.entry("Dummy",                 "Dummy"),
            Map.entry("DetectLastRow",         "DetectLastRow"),
            Map.entry("FieldsChangeSequence",  "FieldsChangeSequence"),
            Map.entry("WriteToLog",            "WriteToLog"),
            Map.entry("Abort",                 "Abort"),
            Map.entry("ExecProcess",           "ExecProcess"),
            Map.entry("FileExists",            "FileExists"),
            Map.entry("FileLocked",            "FileLocked"),
            Map.entry("TableInput",            "TableInput"),
            Map.entry("TableOutput",           "TableOutput"),
            Map.entry("ExcelInput",            "ExcelInput"),
            Map.entry("ExcelOutput",           "ExcelOutput"),
            Map.entry("TypeExitExcelWriter",   "TypeExitExcelWriter"),
            Map.entry("InsertUpdate",          "InsertUpdate"),
            Map.entry("Delete",                "Delete"),
            Map.entry("ExecSQL",               "ExecSQL"),
            Map.entry("GroupBy",               "GroupBy"),
            Map.entry("MemoryGroupBy",         "MemoryGroupBy"),
            Map.entry("Unique",                "Unique"),
            Map.entry("UniqueRowsByHashSet",   "UniqueRowsByHashSet"),
            Map.entry("BlockingStep",          "BlockingStep"),
            Map.entry("Denormaliser",          "Denormaliser"),
            Map.entry("Normaliser",            "Normaliser"),
            Map.entry("Append",                "Append"),
            Map.entry("AppendStreams",         "Append"),
            Map.entry("SortedMerge",           "SortedMerge"),
            Map.entry("MergeJoin",             "MergeJoin"),
            Map.entry("JoinRows",              "JoinRows"),
            Map.entry("MergeRows (diff)",      "MergeRows"),
            Map.entry("MergeRows",             "MergeRows"),
            Map.entry("MultiwayMergeJoin",     "MultiwayMergeJoin"),
            Map.entry("SwitchCase",            "SwitchCase"),
            Map.entry("Mapping (Sub-transformation)", "Mapping"),
            Map.entry("Mapping",               "Mapping"),
            Map.entry("SimpleMapping",         "SimpleMapping"),
            Map.entry("MappingInput",          "MappingInput"),
            Map.entry("MappingOutput",         "MappingOutput")
    );

    private final StepXmlMapperRegistry mapperRegistry;

    public KtrParser() {
        this(StepXmlMapperRegistry.withDefaults());
    }

    public KtrParser(StepXmlMapperRegistry mapperRegistry) {
        this.mapperRegistry = mapperRegistry;
    }

    /**
     * Parses a KTR XML stream into a {@link TransformationDefinition}.
     *
     * @param ktrXml input stream of a {@code .ktr} file
     * @return parsed transformation definition
     */
    public TransformationDefinition parse(InputStream ktrXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Prevent XXE
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(ktrXml);
        doc.getDocumentElement().normalize();

        // Pre-compute field schemas (stepName → ordered field names) and hop topology
        // so KtrParser can resolve column names to 0-based indices before emitting YAML.
        Map<String, List<String>> fieldSchemas  = buildFieldSchemas(doc);
        Map<String, String>       upstreamOf    = buildUpstreamMap(doc);
        Map<String, List<String>> downstreamOf  = buildDownstreamMap(doc);

        TransformationDefinition def = new TransformationDefinition();
        def.name  = extractName(doc);
        def.steps = extractSteps(doc, fieldSchemas, upstreamOf, downstreamOf);
        def.hops  = extractHops(doc);
        return def;
    }

    // -------------------------------------------------------------------------

    /**
     * Builds a map of stepName → ordered field names by scanning every step's
     * {@code <fields>/<field>/<name>} declarations.
     */
    private static Map<String, List<String>> buildFieldSchemas(Document doc) {
        Map<String, List<String>> schemas = new LinkedHashMap<>();
        NodeList stepNodes = doc.getDocumentElement().getElementsByTagName("step");
        for (int i = 0; i < stepNodes.getLength(); i++) {
            Element el = (Element) stepNodes.item(i);
            if (!el.getParentNode().equals(doc.getDocumentElement())) continue;
            String stepName = text(el, "name");
            if (stepName == null) continue;
            List<String> names = new ArrayList<>();
            NodeList fieldsEls = el.getElementsByTagName("fields");
            if (fieldsEls.getLength() > 0) {
                Element fields = (Element) fieldsEls.item(0);
                NodeList fieldEls = fields.getElementsByTagName("field");
                for (int j = 0; j < fieldEls.getLength(); j++) {
                    String fname = text((Element) fieldEls.item(j), "name");
                    if (fname != null && !fname.isBlank()) names.add(fname);
                }
            }
            if (!names.isEmpty()) schemas.put(stepName, names);
        }
        return schemas;
    }

    /**
     * Builds a map of stepName → first upstream step name by scanning {@code <hop>} elements.
     * Used to walk the pipeline backward when resolving column names.
     */
    private static Map<String, String> buildUpstreamMap(Document doc) {
        Map<String, String> upstream = new HashMap<>();
        NodeList hopNodes = doc.getElementsByTagName("hop");
        for (int i = 0; i < hopNodes.getLength(); i++) {
            Element el = (Element) hopNodes.item(i);
            String from = text(el, "from");
            String to   = text(el, "to");
            if (from != null && to != null) upstream.putIfAbsent(to, from);
        }
        return upstream;
    }

    /**
     * Builds a map of stepName → list of downstream step names by scanning {@code <hop>} elements.
     * Used to infer routing targets for FilterRows when send_true_to / send_false_to are absent.
     */
    private static Map<String, List<String>> buildDownstreamMap(Document doc) {
        Map<String, List<String>> downstream = new LinkedHashMap<>();
        NodeList hopNodes = doc.getElementsByTagName("hop");
        for (int i = 0; i < hopNodes.getLength(); i++) {
            Element el = (Element) hopNodes.item(i);
            String from    = text(el, "from");
            String to      = text(el, "to");
            String enabled = text(el, "enabled");
            if (from != null && to != null && !"N".equalsIgnoreCase(enabled)) {
                downstream.computeIfAbsent(from, k -> new ArrayList<>()).add(to);
            }
        }
        return downstream;
    }

    /**
     * Resolves a column name in {@code params} to a 0-based index by walking
     * upstream in the hop graph until a step with a known field schema is found.
     */
    private static void resolveColumnName(Map<String, String> params, String stepName,
                                          Map<String, String> upstreamOf,
                                          Map<String, List<String>> fieldSchemas) {
        String col = params.get("column");
        if (col == null || isInteger(col)) return; // already numeric or absent
        List<String> schema = findUpstreamSchema(stepName, upstreamOf, fieldSchemas);
        if (schema == null) return;
        int idx = schema.indexOf(col);
        if (idx >= 0) params.put("column", String.valueOf(idx));
        // else: leave the name as-is; step will default to 0 with a warning
    }

    private static List<String> findUpstreamSchema(String stepName,
                                                    Map<String, String> upstreamOf,
                                                    Map<String, List<String>> fieldSchemas) {
        String current = stepName;
        for (int depth = 0; depth < 20; depth++) {
            String up = upstreamOf.get(current);
            if (up == null) return null;
            List<String> schema = fieldSchemas.get(up);
            if (schema != null) return schema;
            current = up;
        }
        return null;
    }

    private static boolean isInteger(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private String extractName(Document doc) {
        NodeList infoNodes = doc.getElementsByTagName("info");
        if (infoNodes.getLength() > 0) {
            Element info = (Element) infoNodes.item(0);
            NodeList nameNodes = info.getElementsByTagName("name");
            if (nameNodes.getLength() > 0) {
                return nameNodes.item(0).getTextContent().trim();
            }
        }
        // fallback: root <name>
        NodeList names = doc.getElementsByTagName("name");
        return names.getLength() > 0 ? names.item(0).getTextContent().trim() : "unnamed";
    }

    private List<StepDefinition> extractSteps(Document doc,
                                               Map<String, List<String>> fieldSchemas,
                                               Map<String, String> upstreamOf,
                                               Map<String, List<String>> downstreamOf) {
        List<StepDefinition> steps = new ArrayList<>();
        NodeList stepNodes = doc.getDocumentElement().getElementsByTagName("step");
        for (int i = 0; i < stepNodes.getLength(); i++) {
            Element el = (Element) stepNodes.item(i);
            // Only direct <step> children of root (not nested inside <step_error_handling> etc.)
            if (!el.getParentNode().equals(doc.getDocumentElement())) continue;

            StepDefinition sd = new StepDefinition();
            sd.id   = text(el, "name");
            sd.type = normalizeType(text(el, "type"));

            StepXmlMapper mapper = mapperRegistry.get(sd.type);
            sd.params = mapper.map(el);

            if ("FilterRows".equals(sd.type)) {
                // Resolve column names to 0-based indices.
                resolveColumnName(sd.params, sd.id, upstreamOf, fieldSchemas);
                // Inject trueStep/falseStep from hop topology when absent from step XML.
                injectFilterRouting(sd.params, sd.id, downstreamOf);
            } else if ("SortRows".equals(sd.type)) {
                resolveColumnName(sd.params, sd.id, upstreamOf, fieldSchemas);
            }

            steps.add(sd);
        }
        return steps;
    }

    /**
     * When {@code <send_true_to>} / {@code <send_false_to>} are absent from the FilterRows
     * step XML, infer routing from the hop graph:
     * <ul>
     *   <li>1 downstream hop → that step receives matching (true) rows; false rows discarded</li>
     *   <li>2 downstream hops → first is true, second is false</li>
     * </ul>
     */
    private static void injectFilterRouting(Map<String, String> params, String stepId,
                                             Map<String, List<String>> downstreamOf) {
        if (params.containsKey("trueStep")) return; // already set by mapper from step XML
        List<String> targets = downstreamOf.getOrDefault(stepId, List.of());
        if (!targets.isEmpty()) params.put("trueStep",  targets.get(0));
        if (targets.size() >= 2) params.put("falseStep", targets.get(1));
    }

    private List<HopDefinition> extractHops(Document doc) {
        List<HopDefinition> hops = new ArrayList<>();
        // Hops are in <order><hop> or directly <hop> under root
        NodeList hopNodes = doc.getElementsByTagName("hop");
        for (int i = 0; i < hopNodes.getLength(); i++) {
            Element el = (Element) hopNodes.item(i);
            HopDefinition hop = new HopDefinition();
            hop.from    = text(el, "from");
            hop.to      = text(el, "to");
            String ena  = text(el, "enabled");
            hop.enabled = !"N".equalsIgnoreCase(ena); // default true unless explicitly "N"
            hops.add(hop);
        }
        return hops;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        return nodes.item(0).getTextContent().trim();
    }

    static String normalizeType(String pentahoType) {
        if (pentahoType == null) return "Unknown";
        return TYPE_MAP.getOrDefault(pentahoType, pentahoType);
    }
}
