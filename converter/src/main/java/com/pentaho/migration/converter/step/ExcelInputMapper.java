package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code ExcelInput} XML to our {@code ExcelInput} step params. */
public final class ExcelInputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // First <file>/<name>
        NodeList fileNodes = e.getElementsByTagName("file");
        if (fileNodes.getLength() > 0) {
            put(p, "filePath", child((Element) fileNodes.item(0), "name"));
        }

        // First <sheets>/<sheet>/<name>
        NodeList sheetNodes = e.getElementsByTagName("sheet");
        if (sheetNodes.getLength() > 0) {
            put(p, "sheetName", child((Element) sheetNodes.item(0), "name"));
        }

        put(p, "hasHeader", yesNo(child(e, "header", "Y")));
        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
