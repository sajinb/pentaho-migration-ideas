package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code SortRows} XML to our {@code SortRows} step params.
 *
 * <p>Pentaho stores sort fields as {@code <fields>/<field>/<name>} elements.
 * We emit them as a comma-separated {@code columns} param (column names, not indices).
 * {@code SortRowsStep.configure()} accepts both 0-based integer indices and column names.
 */
public final class SortRowsMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Collect field names in declaration order
        NodeList fieldNodes = e.getElementsByTagName("field");
        List<String> cols = new ArrayList<>();
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element field = (Element) fieldNodes.item(i);
            String name = child(field, "name");
            if (name != null && !name.isBlank()) cols.add(name);
        }
        if (!cols.isEmpty()) {
            p.put("columns", String.join(",", cols));
        }

        String chunkSizeMb = child(e, "sort_size", null);
        if (chunkSizeMb != null) p.put("chunkSizeMb", chunkSizeMb);

        return p;
    }
}
