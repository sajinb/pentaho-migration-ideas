package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/** Maps Pentaho {@code TextFileOutput} XML to our {@code TextFileOutput} step params. */
public final class TextFileOutputMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // <file>/<name> holds the output path; <separator> and <header> may be inside <file>
        // or at the step level depending on the Pentaho KTR export version.
        String filePath = null;
        NodeList fileNodes = e.getElementsByTagName("file");
        if (fileNodes.getLength() > 0) {
            Element fileEl = (Element) fileNodes.item(0);
            filePath = child(fileEl, "name");
            // Prefer inside <file>, fall back to step level
            String sep = child(fileEl, "separator");
            if (sep == null) sep = child(e, "separator");
            put(p, "separator", sep != null ? sep : ",");
            String hdr = child(fileEl, "header");
            if (hdr == null) hdr = child(e, "header");
            put(p, "writeHeader", yesNo(hdr != null ? hdr : "Y"));
        } else {
            put(p, "separator",   child(e, "separator", ","));
            put(p, "writeHeader", yesNo(child(e, "header", "Y")));
        }
        // Fallback: top-level <filename> used when <file> has no <name>, or <file> is absent
        if (filePath == null || filePath.isBlank()) {
            filePath = child(e, "filename");
        }
        put(p, "filePath", filePath);

        // <fields>/<field>/<name> — explicit output column list (order matters)
        List<String> outputFields = new ArrayList<>();
        NodeList fields = e.getElementsByTagName("fields");
        if (fields.getLength() > 0) {
            Element fieldsEl = (Element) fields.item(0);
            NodeList fieldEls = fieldsEl.getElementsByTagName("field");
            for (int i = 0; i < fieldEls.getLength(); i++) {
                Element fieldEl = (Element) fieldEls.item(i);
                String name = child(fieldEl, "name");
                if (name != null && !name.isBlank()) outputFields.add(name);
            }
        }
        if (!outputFields.isEmpty()) p.put("outputFields", String.join(",", outputFields));

        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
