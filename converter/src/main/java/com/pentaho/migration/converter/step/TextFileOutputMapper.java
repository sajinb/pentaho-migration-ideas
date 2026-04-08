package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code TextFileOutput} XML to our {@code TextFileOutput} step params. */
public final class TextFileOutputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // <file>/<name> holds the output path
        NodeList fileNodes = e.getElementsByTagName("file");
        if (fileNodes.getLength() > 0) {
            Element fileEl = (Element) fileNodes.item(0);
            put(p, "filePath",    child(fileEl, "name"));
            put(p, "separator",   child(fileEl, "separator", ","));
            String header = child(fileEl, "header", "Y");
            put(p, "writeHeader", yesNo(header));
        } else {
            // fallback — sometimes written directly on step
            put(p, "filePath",    child(e, "filename"));
        }

        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
