package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code ExcelOutput} / {@code TypeExitExcelWriter} XML to our step params. */
public final class ExcelOutputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();
        put(p, "filePath",    child(e, "filename"));
        put(p, "sheetName",   child(e, "sheetname", "Sheet1"));
        put(p, "writeHeader", yesNo(child(e, "header", "Y")));
        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
