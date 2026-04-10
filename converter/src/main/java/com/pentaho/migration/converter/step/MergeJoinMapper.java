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
 * <p>Two XML formats exist depending on the KTR version:
 *
 * <p><b>Format A</b> (Pentaho 8.x/9.x — flat key containers):
 * <pre>
 * &lt;join_type&gt;INNER&lt;/join_type&gt;
 * &lt;step1&gt;Sort Customers&lt;/step1&gt;
 * &lt;step2&gt;Sort Orders&lt;/step2&gt;
 * &lt;keys_1&gt;&lt;key&gt;customer_id&lt;/key&gt;&lt;/keys_1&gt;
 * &lt;keys_2&gt;&lt;key&gt;customer_id&lt;/key&gt;&lt;/keys_2&gt;
 * </pre>
 *
 * <p><b>Format B</b> (alternate — nested key containers with {@code <name>} child):
 * <pre>
 * &lt;key_fields1&gt;&lt;key&gt;&lt;name&gt;user_id&lt;/name&gt;&lt;/key&gt;&lt;/key_fields1&gt;
 * &lt;key_fields2&gt;&lt;key&gt;&lt;name&gt;user_id&lt;/name&gt;&lt;/key&gt;&lt;/key_fields2&gt;
 * </pre>
 * (step1/step2 may be absent in Format B — KtrParser infers them from hop topology)
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code joinType}   — INNER (default), LEFT OUTER, RIGHT OUTER, FULL OUTER</li>
 *   <li>{@code step1}      — name of the left-side input step (may be absent; inferred by KtrParser)</li>
 *   <li>{@code step2}      — name of the right-side input step (may be absent; inferred by KtrParser)</li>
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

        // Left keys: try <keys_1> (Format A) then <key_fields1> (Format B)
        String leftKeys = collectKeys(e, "keys_1");
        if (leftKeys == null) leftKeys = collectKeys(e, "key_fields1");
        put(p, "leftKeys", leftKeys);

        // Right keys: try <keys_2> (Format A) then <key_fields2> (Format B)
        String rightKeys = collectKeys(e, "keys_2");
        if (rightKeys == null) rightKeys = collectKeys(e, "key_fields2");
        put(p, "rightKeys", rightKeys);

        return p;
    }

    /**
     * Collects all key column names inside the named container element.
     *
     * <p>Handles two key formats:
     * <ul>
     *   <li>{@code <key>columnName</key>} — direct text content (Format A)</li>
     *   <li>{@code <key><name>columnName</name></key>} — nested {@code <name>} child (Format B)</li>
     * </ul>
     */
    private static String collectKeys(Element e, String containerTag) {
        NodeList containers = e.getElementsByTagName(containerTag);
        if (containers.getLength() == 0) return null;
        Element container = (Element) containers.item(0);
        NodeList keys = container.getElementsByTagName("key");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < keys.getLength(); i++) {
            Element keyEl = (Element) keys.item(i);
            // Prefer <name> child (Format B); fall back to direct text (Format A)
            NodeList nameEls = keyEl.getElementsByTagName("name");
            String k = (nameEls.getLength() > 0)
                    ? nameEls.item(0).getTextContent().trim()
                    : keyEl.getTextContent().trim();
            if (!k.isBlank()) names.add(k);
        }
        return names.isEmpty() ? null : String.join(",", names);
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
