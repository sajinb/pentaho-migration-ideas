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
import java.util.Set;

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
            Map.entry("ScriptValueMod",        "ScriptValueMod"),
            Map.entry("ModifiedJavaScriptValue", "ScriptValueMod"),
            Map.entry("GroupBy",               "GroupBy"),
            Map.entry("MemoryGroupBy",         "MemoryGroupBy"),
            Map.entry("Unique",                "Unique"),
            Map.entry("UniqueRows",            "Unique"),
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
        Map<String, List<String>> fieldSchemas    = buildFieldSchemas(doc);
        Map<String, String>       upstreamOf      = buildUpstreamMap(doc);
        Map<String, List<String>> downstreamOf    = buildDownstreamMap(doc);
        // All upstreams per step (used to infer MergeJoin step1/step2 when absent from XML)
        Map<String, List<String>> allUpstreamsOf  = buildAllUpstreamsMap(doc);

        // Some Pentaho KTRs do NOT put the StreamLookup lookup-stream connection in <order>.
        // Instead the lookup source step is only referenced inside the step XML via <from>.
        // Inject those missing connections into the topology maps NOW so that schema
        // propagation and column-index resolution can see both inputs.
        List<HopDefinition> syntheticHops =
                injectStreamLookupConnections(doc, allUpstreamsOf, downstreamOf);

        // Propagate schemas through intermediate steps (SortRows pass-through,
        // GroupBy → [groupKeys+aggNames], MergeJoin → [left+right], Formula → [in+newField]).
        propagateSchemas(doc, fieldSchemas, upstreamOf, allUpstreamsOf);

        TransformationDefinition def = new TransformationDefinition();
        def.name       = extractName(doc);
        def.parameters = extractParameters(doc);
        def.steps      = extractSteps(doc, fieldSchemas, upstreamOf, downstreamOf, allUpstreamsOf);
        def.hops       = extractHops(doc);
        // Append synthetic hops so the executor wires both inputs to StreamLookup steps
        def.hops.addAll(syntheticHops);
        return def;
    }

    // -------------------------------------------------------------------------

    /**
     * Step types whose {@code <fields>/<field>/<name>} elements declare the output column schema.
     * Only source steps actually enumerate all output fields this way.
     * Transform steps (SortRows, GroupBy…) use {@code <fields>} for other purposes
     * (sort keys, aggregate definitions) and must NOT be treated as schema declarations.
     */
    private static final Set<String> SCHEMA_SOURCE_TYPES = Set.of(
            "CsvInput", "TextFileInput", "ExcelInput",
            "RowGenerator", "PropertyInput", "GetFileNames", "SystemInfo", "JsonInput"
    );

    /**
     * Builds a map of stepName → ordered field names by scanning {@code <fields>/<field>/<name>}
     * declarations on <em>source</em> steps only.
     *
     * <p>Non-source steps (SortRows, GroupBy, …) also use {@code <fields>} for different
     * purposes (sort keys, aggregate definitions). Collecting those would produce incomplete
     * or wrong schemas that break downstream column resolution.
     */
    private static Map<String, List<String>> buildFieldSchemas(Document doc) {
        Map<String, List<String>> schemas = new LinkedHashMap<>();
        NodeList stepNodes = doc.getDocumentElement().getElementsByTagName("step");
        for (int i = 0; i < stepNodes.getLength(); i++) {
            Element el = (Element) stepNodes.item(i);
            if (!el.getParentNode().equals(doc.getDocumentElement())) continue;
            String stepName = text(el, "name");
            String stepType = normalizeType(text(el, "type"));
            if (stepName == null || !SCHEMA_SOURCE_TYPES.contains(stepType)) continue;
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
     * Builds a map of stepName → ALL upstream step names (in hop-document order).
     * Unlike {@link #buildUpstreamMap} (which keeps only the first), this captures
     * all inputs — necessary for MergeJoin (two inputs) when step1/step2 are absent from XML.
     */
    private static Map<String, List<String>> buildAllUpstreamsMap(Document doc) {
        Map<String, List<String>> allUpstreams = new LinkedHashMap<>();
        NodeList hopNodes = doc.getElementsByTagName("hop");
        for (int i = 0; i < hopNodes.getLength(); i++) {
            Element el = (Element) hopNodes.item(i);
            String from    = text(el, "from");
            String to      = text(el, "to");
            String enabled = text(el, "enabled");
            if (from != null && to != null && !"N".equalsIgnoreCase(enabled)) {
                allUpstreams.computeIfAbsent(to, k -> new ArrayList<>()).add(from);
            }
        }
        return allUpstreams;
    }

    /**
     * Propagates field schemas through intermediate steps in up to N passes
     * (handles non-topological document order).
     *
     * <p>Rules:
     * <ul>
     *   <li>SortRows, FilterRows, Unique, etc. → pass-through (same schema as upstream)</li>
     *   <li>GroupBy → [group column names + aggregate output names]</li>
     *   <li>MergeJoin → [step1 schema + step2 schema]</li>
     *   <li>Formula → [upstream schema + new field name]</li>
     * </ul>
     */
    private static void propagateSchemas(Document doc,
                                          Map<String, List<String>> fieldSchemas,
                                          Map<String, String> upstreamOf,
                                          Map<String, List<String>> allUpstreamsOf) {
        NodeList stepNodes = doc.getDocumentElement().getElementsByTagName("step");
        boolean changed = true;
        for (int pass = 0; pass < 15 && changed; pass++) {
            changed = false;
            for (int i = 0; i < stepNodes.getLength(); i++) {
                Element el = (Element) stepNodes.item(i);
                if (!el.getParentNode().equals(doc.getDocumentElement())) continue;
                String stepName = text(el, "name");
                String stepType = normalizeType(text(el, "type"));
                if (stepName == null || fieldSchemas.containsKey(stepName)) continue;

                List<String> schema = computeStepOutputSchema(el, stepName, stepType,
                                                               fieldSchemas, upstreamOf,
                                                               allUpstreamsOf);
                if (schema != null) {
                    fieldSchemas.put(stepName, schema);
                    changed = true;
                }
            }
        }
    }

    private static List<String> computeStepOutputSchema(Element el,
                                                          String stepName, String stepType,
                                                          Map<String, List<String>> fieldSchemas,
                                                          Map<String, String> upstreamOf,
                                                          Map<String, List<String>> allUpstreamsOf) {
        return switch (stepType) {
            // Pass-through steps: output schema == upstream schema
            case "SortRows", "FilterRows", "Unique", "UniqueRowsByHashSet",
                 "BlockingStep", "Normaliser", "WriteToLog", "SelectValues",
                 "SwitchCase" -> {
                String up = upstreamOf.get(stepName);
                yield up != null ? fieldSchemas.get(up) : null;
            }
            case "GroupBy", "MemoryGroupBy" -> computeGroupByOutputSchema(el);
            case "MergeJoin"               -> computeMergeJoinOutputSchema(el, stepName,
                                                                             fieldSchemas,
                                                                             allUpstreamsOf);
            case "Formula"                 -> computeFormulaOutputSchema(el, stepName,
                                                                          fieldSchemas, upstreamOf);
            case "Calculator"              -> computeCalculatorOutputSchema(el, stepName,
                                                                             fieldSchemas, upstreamOf);
            case "ScriptValueMod"          -> computeScriptOutputSchema(el, stepName,
                                                                         fieldSchemas, upstreamOf);
            case "StreamLookup"            -> computeStreamLookupOutputSchema(el, stepName,
                                                                               fieldSchemas,
                                                                               allUpstreamsOf,
                                                                               upstreamOf);
            default -> null;
        };
    }

    /** Calculator output = upstream schema + [field_name from &lt;calculation&gt;]. */
    private static List<String> computeCalculatorOutputSchema(Element el, String stepName,
                                                                Map<String, List<String>> fieldSchemas,
                                                                Map<String, String> upstreamOf) {
        String up = upstreamOf.get(stepName);
        List<String> upSchema = up != null ? fieldSchemas.get(up) : null;

        String newField = null;
        NodeList calcEls = el.getElementsByTagName("calculation");
        if (calcEls.getLength() > 0) {
            newField = text((Element) calcEls.item(0), "field_name");
        }

        if (newField == null || newField.isBlank()) return upSchema;
        List<String> schema = upSchema != null ? new ArrayList<>(upSchema) : new ArrayList<>();
        schema.add(newField);
        return schema;
    }

    /** GroupBy output = [group key names … , aggregate output names …]. */
    private static List<String> computeGroupByOutputSchema(Element el) {
        List<String> schema = new ArrayList<>();

        NodeList groupEls = el.getElementsByTagName("group");
        if (groupEls.getLength() > 0) {
            Element group = (Element) groupEls.item(0);
            NodeList fields = group.getElementsByTagName("field");
            for (int i = 0; i < fields.getLength(); i++) {
                String name = text((Element) fields.item(i), "name");
                if (name != null && !name.isBlank()) schema.add(name);
            }
        }

        // Aggregate output names: old format <fields>/<field>/<aggregate>,
        // new format <aggregates>/<aggregate>/<name>
        NodeList fieldsEls = el.getElementsByTagName("fields");
        if (fieldsEls.getLength() > 0) {
            Element fields = (Element) fieldsEls.item(0);
            NodeList fieldEls = fields.getElementsByTagName("field");
            for (int i = 0; i < fieldEls.getLength(); i++) {
                String aggName = text((Element) fieldEls.item(i), "aggregate");
                if (aggName != null && !aggName.isBlank()) schema.add(aggName);
            }
        } else {
            NodeList aggContainers = el.getElementsByTagName("aggregates");
            if (aggContainers.getLength() > 0) {
                Element aggs = (Element) aggContainers.item(0);
                NodeList aggEls = aggs.getElementsByTagName("aggregate");
                for (int i = 0; i < aggEls.getLength(); i++) {
                    String aggName = text((Element) aggEls.item(i), "name");
                    if (aggName != null && !aggName.isBlank()) schema.add(aggName);
                }
            }
        }

        return schema.isEmpty() ? null : schema;
    }

    /**
     * MergeJoin output = left step's schema + right step's schema.
     * step1/step2 are read from the XML; when absent (newer Pentaho format omits them),
     * they are inferred from the hop topology via {@code allUpstreamsOf}.
     */
    private static List<String> computeMergeJoinOutputSchema(Element el, String stepName,
                                                               Map<String, List<String>> fieldSchemas,
                                                               Map<String, List<String>> allUpstreamsOf) {
        String step1 = text(el, "step1");
        String step2 = text(el, "step2");
        // Infer from hop topology when absent from XML
        if (step1 == null || step2 == null) {
            List<String> ups = allUpstreamsOf.getOrDefault(stepName, List.of());
            if (step1 == null && !ups.isEmpty())     step1 = ups.get(0);
            if (step2 == null && ups.size() >= 2)    step2 = ups.get(1);
        }
        List<String> left  = step1 != null ? fieldSchemas.get(step1) : null;
        List<String> right = step2 != null ? fieldSchemas.get(step2) : null;
        if (left == null || right == null) return null; // upstream not yet resolved
        List<String> schema = new ArrayList<>(left);
        schema.addAll(right);
        return schema;
    }

    /** Formula output = upstream schema + [new field name]. */
    private static List<String> computeFormulaOutputSchema(Element el, String stepName,
                                                             Map<String, List<String>> fieldSchemas,
                                                             Map<String, String> upstreamOf) {
        String up = upstreamOf.get(stepName);
        List<String> upSchema = up != null ? fieldSchemas.get(up) : null;

        // Extract new field name from <formula><field_name>…</field_name></formula>
        String newField = null;
        NodeList formulaEls = el.getElementsByTagName("formula");
        if (formulaEls.getLength() > 0) {
            newField = text((Element) formulaEls.item(0), "field_name");
        }

        if (newField == null || newField.isBlank()) return upSchema;
        List<String> schema = upSchema != null ? new ArrayList<>(upSchema) : new ArrayList<>();
        schema.add(newField);
        return schema;
    }

    /**
     * ScriptValueMod output = upstream schema + [new output field names from &lt;fields&gt;].
     * In-place updates (field names that already exist upstream) do not extend the schema.
     */
    private static List<String> computeScriptOutputSchema(Element el, String stepName,
                                                            Map<String, List<String>> fieldSchemas,
                                                            Map<String, String> upstreamOf) {
        String up = upstreamOf.get(stepName);
        List<String> upSchema = up != null ? fieldSchemas.get(up) : null;
        List<String> schema   = upSchema != null ? new ArrayList<>(upSchema) : new ArrayList<>();

        // Output fields defined directly under the step's <fields> element
        NodeList children = el.getChildNodes();
        Element outputFieldsEl = null;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                String nodeName = child.getLocalName() != null ? child.getLocalName() : child.getNodeName();
                if ("fields".equals(nodeName)) { outputFieldsEl = child; break; }
            }
        }
        if (outputFieldsEl == null) {
            NodeList all = el.getElementsByTagName("fields");
            if (all.getLength() > 0) outputFieldsEl = (Element) all.item(0);
        }

        if (outputFieldsEl != null) {
            NodeList fieldEls = outputFieldsEl.getElementsByTagName("field");
            for (int i = 0; i < fieldEls.getLength(); i++) {
                String name = text((Element) fieldEls.item(i), "name");
                if (name != null && !name.isBlank() && !schema.contains(name)) {
                    schema.add(name);
                }
            }
        }
        return schema.isEmpty() ? null : schema;
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
                                               Map<String, List<String>> downstreamOf,
                                               Map<String, List<String>> allUpstreamsOf) {
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
                resolveColumnNames(sd.params, "columns", sd.id, upstreamOf, fieldSchemas);
            } else if ("MergeJoin".equals(sd.type)) {
                // Inject step1/step2 from hop topology when absent from step XML
                if (!sd.params.containsKey("step1")) {
                    List<String> ups = allUpstreamsOf.getOrDefault(sd.id, List.of());
                    if (!ups.isEmpty())     sd.params.put("step1", ups.get(0));
                    if (ups.size() >= 2)    sd.params.put("step2", ups.get(1));
                }
                resolveMergeJoinColumns(sd.params, upstreamOf, fieldSchemas);
            } else if ("GroupBy".equals(sd.type) || "MemoryGroupBy".equals(sd.type)) {
                resolveColumnNames(sd.params, "groupColumns", sd.id, upstreamOf, fieldSchemas);
                resolveColumnNames(sd.params, "aggColumns",   sd.id, upstreamOf, fieldSchemas);
            } else if ("Formula".equals(sd.type)) {
                // Inject upstream field names so FormulaStep can resolve [name] references.
                List<String> upSchema = findUpstreamSchema(sd.id, upstreamOf, fieldSchemas);
                if (upSchema != null) {
                    sd.params.put("fieldNames", String.join(",", upSchema));
                }
            } else if ("Calculator".equals(sd.type)) {
                // Resolve fieldA / fieldB (column names) to colA / colB (0-based indices).
                resolveToIndex(sd.params, "fieldA", "colA", sd.id, upstreamOf, fieldSchemas);
                resolveToIndex(sd.params, "fieldB", "colB", sd.id, upstreamOf, fieldSchemas);
            } else if ("ScriptValueMod".equals(sd.type)) {
                // Inject upstream field names so ScriptValueModStep can expose them as JS variables.
                List<String> upSchema = findUpstreamSchema(sd.id, upstreamOf, fieldSchemas);
                if (upSchema != null) {
                    sd.params.put("fieldNames", String.join(",", upSchema));
                }
            } else if ("SwitchCase".equals(sd.type)) {
                // Resolve field_name → column (0-based index) using upstream schema.
                String fn = sd.params.get("field_name");
                if (fn != null && !isInteger(fn)) {
                    List<String> schema = findUpstreamSchema(sd.id, upstreamOf, fieldSchemas);
                    if (schema != null) {
                        int idx = schema.indexOf(fn);
                        if (idx >= 0) sd.params.put("column", String.valueOf(idx));
                    }
                }
            } else if ("StreamLookup".equals(sd.type)) {
                // Determine which input index is the lookup stream vs. the main stream.
                String lookupStepName = sd.params.get("lookupStep");
                List<String> ups = allUpstreamsOf.getOrDefault(sd.id, List.of());

                int lookupInputIdx = 1; // default: second input is lookup
                if (lookupStepName != null) {
                    int idx = ups.indexOf(lookupStepName);
                    if (idx >= 0) lookupInputIdx = idx;
                }
                sd.params.put("lookupInputIndex", String.valueOf(lookupInputIdx));

                int mainInputIdx      = lookupInputIdx == 0 ? 1 : 0;
                String mainUpstream   = ups.size() > mainInputIdx   ? ups.get(mainInputIdx)   : null;
                String lookupUpstream = ups.size() > lookupInputIdx ? ups.get(lookupInputIdx) : null;

                // Resolve key field names in the main stream
                if (mainUpstream != null) {
                    List<String> mainSchema = fieldSchemas.get(mainUpstream);
                    if (mainSchema == null) mainSchema = findUpstreamSchema(mainUpstream, upstreamOf, fieldSchemas);
                    resolveToIndexParam(sd.params, "keyStream",   "keyStreamCols", mainSchema);
                }

                // Resolve key and value field names in the lookup stream
                if (lookupUpstream != null) {
                    List<String> lookupSchema = fieldSchemas.get(lookupUpstream);
                    if (lookupSchema == null) lookupSchema = findUpstreamSchema(lookupUpstream, upstreamOf, fieldSchemas);
                    resolveToIndexParam(sd.params, "keyLookup",   "keyLookupCols",  lookupSchema);
                    resolveToIndexParam(sd.params, "valueFields", "valueFieldCols", lookupSchema);
                }
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

    /**
     * Resolves comma-separated column names in {@code paramKey} to 0-based indices by
     * walking upstream from {@code stepName}. Tokens that are already integers are kept.
     * Tokens that cannot be resolved are left as-is (step will give a clear error at runtime).
     */
    private static void resolveColumnNames(Map<String, String> params, String paramKey,
                                            String stepName,
                                            Map<String, String> upstreamOf,
                                            Map<String, List<String>> fieldSchemas) {
        String raw = params.get(paramKey);
        if (raw == null || raw.isBlank()) return;
        List<String> schema = findUpstreamSchema(stepName, upstreamOf, fieldSchemas);
        if (schema == null) return; // can't resolve — leave names; step will error
        String[] tokens = raw.split(",");
        boolean changed = false;
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].trim();
            if (!isInteger(t)) {
                int idx = schema.indexOf(t);
                if (idx >= 0) { tokens[i] = String.valueOf(idx); changed = true; }
            }
        }
        if (changed) params.put(paramKey, String.join(",", tokens));
    }

    /**
     * Resolves a single named column from {@code srcParam} to a 0-based index in
     * {@code destParam}. If the value is already an integer it is copied unchanged.
     * Used by Calculator to convert {@code fieldA}/{@code fieldB} → {@code colA}/{@code colB}.
     */
    private static void resolveToIndex(Map<String, String> params,
                                        String srcParam, String destParam,
                                        String stepName,
                                        Map<String, String> upstreamOf,
                                        Map<String, List<String>> fieldSchemas) {
        String name = params.get(srcParam);
        if (name == null || name.isBlank()) return;
        if (isInteger(name)) { params.put(destParam, name); return; }
        List<String> schema = findUpstreamSchema(stepName, upstreamOf, fieldSchemas);
        if (schema == null) return;
        int idx = schema.indexOf(name);
        if (idx >= 0) params.put(destParam, String.valueOf(idx));
        // If not found, leave absent — CalculatorStep.configure() will NPE with a clear message
    }

    /**
     * Resolves MergeJoin key column names to 0-based indices.
     * Left keys are resolved against the schema of {@code step1}; right keys against {@code step2}.
     * Writes {@code leftColumns} / {@code rightColumns} into params when resolution succeeds.
     */
    private static void resolveMergeJoinColumns(Map<String, String> params,
                                                 Map<String, String> upstreamOf,
                                                 Map<String, List<String>> fieldSchemas) {
        resolveJoinSide(params, "leftKeys",  "leftColumns",  params.get("step1"), upstreamOf, fieldSchemas);
        resolveJoinSide(params, "rightKeys", "rightColumns", params.get("step2"), upstreamOf, fieldSchemas);
    }

    private static void resolveJoinSide(Map<String, String> params,
                                         String keysParam, String colsParam, String stepName,
                                         Map<String, String> upstreamOf,
                                         Map<String, List<String>> fieldSchemas) {
        String keys = params.get(keysParam);
        if (keys == null || keys.isBlank() || stepName == null) return;

        // Schema may be on the named step itself, or on its upstream source step.
        List<String> schema = fieldSchemas.get(stepName);
        if (schema == null) schema = findUpstreamSchema(stepName, upstreamOf, fieldSchemas);
        if (schema == null) return; // leave as names; MergeJoinStep will error

        String[] names = keys.split(",");
        String[] indices = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            int idx = isInteger(name) ? Integer.parseInt(name) : schema.indexOf(name);
            indices[i] = String.valueOf(Math.max(idx, 0));
        }
        params.put(colsParam, String.join(",", indices));
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

    /**
     * Scans every {@code StreamLookup} step in the document and ensures its lookup-stream
     * connection appears in the topology maps ({@code allUpstreamsOf} and {@code downstreamOf}).
     *
     * <p>In some Pentaho KTR exports the lookup-stream hop is <em>absent</em> from the
     * {@code <order>} section — the lookup source step is only named via {@code <from>} inside
     * the step XML. Without this injection the engine would only receive 1 input iterator and
     * throw an {@code IndexOutOfBoundsException} when trying to access the second input.
     *
     * @return synthetic {@link HopDefinition} objects for connections that were missing, so the
     *         caller can append them to the parsed hop list and the executor can wire them correctly.
     */
    private static List<HopDefinition> injectStreamLookupConnections(
            Document doc,
            Map<String, List<String>> allUpstreamsOf,
            Map<String, List<String>> downstreamOf) {

        List<HopDefinition> synthetic = new ArrayList<>();
        NodeList stepNodes = doc.getDocumentElement().getElementsByTagName("step");
        for (int i = 0; i < stepNodes.getLength(); i++) {
            Element el = (Element) stepNodes.item(i);
            if (!el.getParentNode().equals(doc.getDocumentElement())) continue;
            if (!"StreamLookup".equals(normalizeType(text(el, "type")))) continue;
            String stepName = text(el, "name");
            if (stepName == null) continue;

            // Identify the lookup source step: <from> or <lookupsteps>/<lookupstep>/<name>
            String fromStep = text(el, "from");
            if (fromStep == null || fromStep.isBlank()) {
                NodeList lsNodes = el.getElementsByTagName("lookupstep");
                if (lsNodes.getLength() > 0) fromStep = text((Element) lsNodes.item(0), "name");
            }
            if (fromStep == null || fromStep.isBlank()) continue;

            // Only inject when the lookup hop is missing from <order>
            List<String> ups = allUpstreamsOf.getOrDefault(stepName, List.of());
            if (ups.contains(fromStep)) continue;

            allUpstreamsOf.computeIfAbsent(stepName, k -> new ArrayList<>()).add(fromStep);
            downstreamOf.computeIfAbsent(fromStep,   k -> new ArrayList<>()).add(stepName);

            HopDefinition hop = new HopDefinition();
            hop.from    = fromStep;
            hop.to      = stepName;
            hop.enabled = true;
            synthetic.add(hop);
        }
        return synthetic;
    }

    /**
     * Parses the KTR {@code <parameters>} section into a name→default-value map.
     * Only direct children of {@code <parameters>} are collected to avoid picking up
     * field-level {@code <parameter>} elements inside step definitions.
     */
    private static Map<String, String> extractParameters(Document doc) {
        Map<String, String> params = new LinkedHashMap<>();
        NodeList paramNodes = doc.getElementsByTagName("parameter");
        for (int i = 0; i < paramNodes.getLength(); i++) {
            Element el = (Element) paramNodes.item(i);
            // Only include <parameter> elements whose immediate parent is <parameters>
            if (!"parameters".equals(el.getParentNode().getNodeName())) continue;
            String name       = text(el, "name");
            String defaultVal = text(el, "default_value");
            if (name != null && !name.isBlank()) {
                params.put(name, defaultVal != null ? defaultVal : "");
            }
        }
        return params;
    }

    /**
     * Resolves comma-separated column names in {@code srcParam} to 0-based indices
     * in {@code destParam} using the given schema list.
     * Tokens that are already integers are kept unchanged.
     */
    private static void resolveToIndexParam(Map<String, String> params,
                                             String srcParam, String destParam,
                                             List<String> schema) {
        String raw = params.get(srcParam);
        if (raw == null || raw.isBlank() || schema == null) return;
        String[] names   = raw.split(",");
        String[] indices = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            String name = names[i].trim();
            if (isInteger(name)) {
                indices[i] = name;
            } else {
                int idx = schema.indexOf(name);
                indices[i] = idx >= 0 ? String.valueOf(idx) : "0";
            }
        }
        params.put(destParam, String.join(",", indices));
    }

    /**
     * StreamLookup output schema = main-stream schema + renamed lookup-value field names.
     * The lookup stream is identified by the {@code <from>} element inside the step.
     */
    private static List<String> computeStreamLookupOutputSchema(Element el, String stepName,
                                                                  Map<String, List<String>> fieldSchemas,
                                                                  Map<String, List<String>> allUpstreamsOf,
                                                                  Map<String, String> upstreamOf) {
        String fromStep = text(el, "from");
        if (fromStep == null || fromStep.isBlank()) {
            // Try <lookupsteps>/<lookupstep>/<name>
            NodeList lsNodes = el.getElementsByTagName("lookupstep");
            if (lsNodes.getLength() > 0) fromStep = text((Element) lsNodes.item(0), "name");
        }

        // Identify the main (non-lookup) upstream step
        List<String> ups = allUpstreamsOf.getOrDefault(stepName, List.of());
        String mainUpstream = null;
        for (String up : ups) {
            if (!up.equals(fromStep)) { mainUpstream = up; break; }
        }
        if (mainUpstream == null && !ups.isEmpty()) mainUpstream = ups.get(0);

        List<String> mainSchema = mainUpstream != null ? fieldSchemas.get(mainUpstream) : null;
        if (mainSchema == null && mainUpstream != null) {
            mainSchema = findUpstreamSchema(mainUpstream, upstreamOf, fieldSchemas);
        }
        List<String> schema = mainSchema != null ? new ArrayList<>(mainSchema) : new ArrayList<>();

        // Append lookup output field names (renamed if <rename> present)
        NodeList lookupEls = el.getElementsByTagName("lookup");
        if (lookupEls.getLength() > 0) {
            Element lookup = (Element) lookupEls.item(0);
            NodeList valueEls = lookup.getElementsByTagName("value");
            for (int i = 0; i < valueEls.getLength(); i++) {
                Element val    = (Element) valueEls.item(i);
                String rename  = text(val, "rename");
                String name    = text(val, "name");
                String outName = (rename != null && !rename.isBlank()) ? rename : name;
                if (outName != null && !outName.isBlank()) schema.add(outName);
            }
        }

        return schema.isEmpty() ? null : schema;
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
