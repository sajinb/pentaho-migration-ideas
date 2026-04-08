package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code TextFileInput} XML to our {@code TextFileInput} step params. */
public final class TextFileInputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // First <file>/<name> child
        NodeList fileNodes = e.getElementsByTagName("file");
        if (fileNodes.getLength() > 0) {
            Element fileEl = (Element) fileNodes.item(0);
            put(p, "filePath", child(fileEl, "name"));
        }

        put(p, "separator", child(e, "separator", ","));
        put(p, "enclosure", child(e, "enclosure", "\""));
        put(p, "hasHeader", yesNo(child(e, "header", "Y")));
        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
