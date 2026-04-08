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

        TransformationDefinition def = new TransformationDefinition();
        def.name  = extractName(doc);
        def.steps = extractSteps(doc);
        def.hops  = extractHops(doc);
        return def;
    }

    // -------------------------------------------------------------------------

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

    private List<StepDefinition> extractSteps(Document doc) {
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

            steps.add(sd);
        }
        return steps;
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
