package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code MergeJoin} XML to our {@code MergeJoin} step params.
 *
 * <p>Pentaho XML format:
 * <pre>
 * &lt;join_type&gt;INNER&lt;/join_type&gt;
 * &lt;step1&gt;Sort Customers&lt;/step1&gt;
 * &lt;step2&gt;Sort Orders&lt;/step2&gt;
 * &lt;keys_1&gt;&lt;key&gt;customer_id&lt;/key&gt;&lt;/keys_1&gt;
 * &lt;keys_2&gt;&lt;key&gt;customer_id&lt;/key&gt;&lt;/keys_2&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code joinType}   — INNER (default), LEFT OUTER, RIGHT OUTER, FULL OUTER</li>
 *   <li>{@code step1}      — name of the left-side input step</li>
 *   <li>{@code step2}      — name of the right-side input step</li>
 *   <li>{@code leftKeys}   — comma-separated left key column names (resolved to indices by KtrParser)</li>
 *   <li>{@code rightKeys}  — comma-separated right key column names (resolved to indices by KtrParser)</li>
 * </ul>
 */
public final class MergeJoinMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        put(p, "joinType", child(e, "join_type", "INNER"));
        put(p, "step1",    child(e, "step1"));
        put(p, "step2",    child(e, "step2"));
        put(p, "leftKeys",  collectKeys(e, "keys_1"));
        put(p, "rightKeys", collectKeys(e, "keys_2"));

        return p;
    }

    /** Collects all {@code <key>} text values inside the named container element. */
    private static String collectKeys(Element e, String containerTag) {
        NodeList containers = e.getElementsByTagName(containerTag);
        if (containers.getLength() == 0) return null;
        Element container = (Element) containers.item(0);
        NodeList keys = container.getElementsByTagName("key");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.getLength(); i++) {
            String k = keys.item(i).getTextContent().trim();
            if (!k.isBlank()) names.add(k);
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
