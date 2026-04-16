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
 * <p>Pentaho stores sort fields as {@code <fields>/<field>} elements. Each field has:
 * <ul>
 *   <li>{@code <name>} — column name (resolved to 0-based index by KtrParser)
 *   <li>{@code <ascending>} — {@code Y} (default) or {@code N} for descending
 *   <li>{@code <sort_direction>} — alternative: {@code ascending} or {@code descending}
 * </ul>
 */
public final class SortRowsMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Collect field names and ascending flags in declaration order
        NodeList fieldNodes = e.getElementsByTagName("field");
        List<String> cols = new ArrayList<>();
        List<String> ascList = new ArrayList<>();
        for (int i = 0; i < fieldNodes.getLength(); i++) {
            Element field = (Element) fieldNodes.item(i);
            String name = child(field, "name");
            if (name == null || name.isBlank()) continue;
            cols.add(name);

            // Support both <ascending>Y/N</ascending> and <sort_direction>ascending/descending</sort_direction>
            String asc = child(field, "ascending");
            String dir = child(field, "sort_direction");
            boolean isAscending = true;
            if (asc != null) {
                isAscending = !"N".equalsIgnoreCase(asc.trim());
            } else if (dir != null) {
                isAscending = !"descending".equalsIgnoreCase(dir.trim());
            }
            ascList.add(isAscending ? "true" : "false");
        }
        if (!cols.isEmpty()) {
            p.put("columns",   String.join(",", cols));
            p.put("ascending", String.join(",", ascList));
        }

        String chunkSizeMb = child(e, "sort_size", null);
        if (chunkSizeMb != null) p.put("chunkSizeMb", chunkSizeMb);

        return p;
    }
}
