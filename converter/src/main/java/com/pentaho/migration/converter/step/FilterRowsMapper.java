package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code FilterRows} XML to our {@code FilterRows} step params.
 *
 * <p>Pentaho 8.x/9.x format uses {@code <condition>} with nested elements:
 * <pre>
 * &lt;condition&gt;
 *   &lt;leftvalue&gt;&lt;name&gt;age&lt;/name&gt;&lt;/leftvalue&gt;
 *   &lt;function&gt;GT&lt;/function&gt;
 *   &lt;rightvalue&gt;&lt;value&gt;18&lt;/value&gt;&lt;/rightvalue&gt;
 * &lt;/condition&gt;
 * </pre>
 *
 * <p>Older format uses a flat {@code <compare>} block:
 * <pre>
 * &lt;compare&gt;&lt;fieldname&gt;age&lt;/fieldname&gt;&lt;value&gt;18&lt;/value&gt;&lt;/compare&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code column}   — field name (resolved to 0-based index by KtrParser)</li>
 *   <li>{@code operator} — GT, LT, GTE, LTE, EQ, NEQ (default EQ)</li>
 *   <li>{@code value}    — comparison value as string</li>
 *   <li>{@code trueStep} / {@code falseStep} — routing targets</li>
 * </ul>
 */
public final class FilterRowsMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Routing targets
        put(p, "trueStep",  child(e, "send_true_to"));
        put(p, "falseStep", child(e, "send_false_to"));

        // ── Older <compare> block ──────────────────────────────────────────────
        NodeList compareNodes = e.getElementsByTagName("compare");
        if (compareNodes.getLength() > 0) {
            Element cmp = (Element) compareNodes.item(0);
            put(p, "column",   child(cmp, "fieldname"));
            put(p, "operator", child(cmp, "operator"));  // may be absent → default EQ in step
            put(p, "value",    child(cmp, "value"));
            return p;
        }

        // ── Newer <condition> block ────────────────────────────────────────────
        NodeList condNodes = e.getElementsByTagName("condition");
        if (condNodes.getLength() > 0) {
            Element cond = (Element) condNodes.item(0);

            // <leftvalue><name>age</name></leftvalue>  — textContent = "age"
            put(p, "column",   child(cond, "leftvalue"));

            // <function>GT</function>
            put(p, "operator", child(cond, "function"));

            // <rightvalue><value>18</value></rightvalue>  — textContent = "18"
            String val = child(cond, "rightvalue");
            if (val == null || val.isBlank()) val = child(cond, "rightstring");
            put(p, "value", val);
        }

        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
