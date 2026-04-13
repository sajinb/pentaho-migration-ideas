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

        // Routing targets — Pentaho uses either <send_true_to>/<send_false_to>
        // or <true_step>/<false_step> depending on the KTR version.
        String trueTarget = child(e, "send_true_to");
        if (trueTarget == null) trueTarget = child(e, "true_step");
        put(p, "trueStep",  trueTarget);

        String falseTarget = child(e, "send_false_to");
        if (falseTarget == null) falseTarget = child(e, "false_step");
        put(p, "falseStep", falseTarget);

        // ── Older <compare> block ──────────────────────────────────────────────
        NodeList compareNodes = e.getElementsByTagName("compare");
        if (compareNodes.getLength() > 0) {
            Element cmp = (Element) compareNodes.item(0);

            // Real-world KTRs sometimes nest a <condition> inside <compare>:
            //   <compare><condition><leftvalue>…</leftvalue>…</condition></compare>
            NodeList nestedCond = cmp.getElementsByTagName("condition");
            if (nestedCond.getLength() > 0) {
                // Delegate to condition-style parsing using the nested element.
                Element cond = (Element) nestedCond.item(0);
                put(p, "column",   child(cond, "leftvalue"));
                String rawOp = child(cond, "function");
                put(p, "operator", normalizeOperator(rawOp));
                String val = child(cond, "rightvalue");
                if (val == null || val.isBlank()) val = child(cond, "rightstring");
                if (val == null || val.isBlank()) {
                    NodeList valueEls = cond.getElementsByTagName("value");
                    if (valueEls.getLength() > 0) {
                        val = text((Element) valueEls.item(0), "text");
                    }
                }
                put(p, "value", val);
                return p;
            }

            // Flat <compare><fieldname> format (oldest KTR export style)
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
            // Also handles <leftvalue>ltv_total</leftvalue> (direct text, no child element)
            put(p, "column",   child(cond, "leftvalue"));

            // <function>GT</function>  or  <function>&gt;</function> (XML entity → ">")
            String rawOp = child(cond, "function");
            put(p, "operator", normalizeOperator(rawOp));

            // <rightvalue><value>18</value></rightvalue>  — textContent = "18"
            // Also: <rightvalue/>  with value in <value><text>5000.0</text></value>
            String val = child(cond, "rightvalue");
            if (val == null || val.isBlank()) val = child(cond, "rightstring");
            if (val == null || val.isBlank()) {
                // Pentaho constant format: <value><name>constant</name><text>5000.0</text></value>
                NodeList valueEls = cond.getElementsByTagName("value");
                if (valueEls.getLength() > 0) {
                    val = text((Element) valueEls.item(0), "text");
                }
            }
            put(p, "value", val);
        }

        return p;
    }

    /** Maps XML/symbolic operators to the short codes understood by FilterRowsStep. */
    private static String normalizeOperator(String op) {
        if (op == null) return null;
        return switch (op.trim()) {
            case ">",  "GT"                         -> "GT";
            case "<",  "LT"                         -> "LT";
            case ">=", "=>", "GTE", "GREATER_EQUAL" -> "GTE";  // "=>" is a Pentaho quirk
            case "<=", "=<", "LTE", "LESS_EQUAL"    -> "LTE";
            case "=",  "EQ", "EQUAL"                -> "EQ";
            case "<>", "!=", "NEQ", "NOT_EQUAL"     -> "NEQ";
            default                                  -> op;
        };
    }

    /** Returns trimmed text content of the first descendant with {@code tag}, or {@code null}. */
    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String t = nodes.item(0).getTextContent();
        return t == null ? null : t.trim();
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
