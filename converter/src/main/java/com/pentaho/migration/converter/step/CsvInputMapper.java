package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code CSVInput} / {@code CsvInput} XML to our {@code CsvInput} step params.
 *
 * <p>Two XML formats exist depending on the KTR version:
 *
 * <p><b>Format A</b> (flat — Pentaho 7.x CSV Input step):
 * <pre>
 * &lt;filename&gt;/path/to/file.csv&lt;/filename&gt;
 * &lt;header&gt;Y&lt;/header&gt;
 * &lt;separator&gt;,&lt;/separator&gt;
 * </pre>
 *
 * <p><b>Format B</b> (nested — Pentaho 8.x/9.x TextFileInput-style):
 * <pre>
 * &lt;file&gt;&lt;name&gt;/path/to/file.csv&lt;/name&gt;&lt;/file&gt;
 * &lt;content&gt;&lt;header&gt;Y&lt;/header&gt;&lt;separator&gt;,&lt;/separator&gt;&lt;/content&gt;
 * </pre>
 */
public final class CsvInputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // filePath: try flat <filename> first (Format A), then <file><name> (Format B)
        String filePath = child(e, "filename");
        if (filePath == null || filePath.isBlank()) {
            filePath = childIn(e, "file", "name");
        }
        put(p, "filePath", filePath);

        // hasHeader / separator / enclosure: getElementsByTagName searches all descendants,
        // so both flat <header> and <content><header> are found automatically.
        put(p, "hasHeader", yesNo(child(e, "header", "Y")));

        // Older Pentaho KTR exports use <delimiter> instead of <separator>.
        String sep = child(e, "separator");
        if (sep == null) sep = child(e, "delimiter");
        put(p, "separator", sep != null ? sep : ",");

        put(p, "enclosure", child(e, "enclosure", "\""));
        return p;
    }

    /**
     * Returns trimmed text of {@code childTag} inside the first {@code containerTag}
     * element, or {@code null} if either element is absent.
     */
    private static String childIn(Element parent, String containerTag, String childTag) {
        NodeList containers = parent.getElementsByTagName(containerTag);
        if (containers.getLength() == 0) return null;
        Element container = (Element) containers.item(0);
        NodeList children = container.getElementsByTagName(childTag);
        if (children.getLength() == 0) return null;
        // Ensure child is a direct child of the container to avoid accidentally
        // picking up a deeply nested <name> element (e.g., field names under <fields>).
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getParentNode() == container) {
                String t = children.item(i).getTextContent();
                return t == null ? null : t.trim();
            }
        }
        return null;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
