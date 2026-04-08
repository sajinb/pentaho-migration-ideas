package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code CSVInput} / {@code CsvInput} XML to our {@code CsvInput} step params. */
public final class CsvInputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();
        put(p, "filePath",  child(e, "filename"));
        put(p, "hasHeader", yesNo(child(e, "header", "Y")));
        put(p, "separator", child(e, "separator", ","));
        put(p, "enclosure", child(e, "enclosure", "\""));
        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
