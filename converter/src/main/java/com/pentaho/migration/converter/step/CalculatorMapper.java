package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Pentaho {@code Calculator} XML to our {@code Calculator} step params.
 *
 * <p>Pentaho XML format (single calculation — the most common case):
 * <pre>
 * &lt;step&gt;
 *   &lt;type&gt;Calculator&lt;/type&gt;
 *   &lt;calculation&gt;
 *     &lt;field_name&gt;tax_amount&lt;/field_name&gt;
 *     &lt;calc_type&gt;MULTIPLY&lt;/calc_type&gt;
 *     &lt;field_a&gt;sale_price&lt;/field_a&gt;
 *     &lt;field_b&gt;tax_rate_constant&lt;/field_b&gt;
 *     &lt;value_type&gt;Number&lt;/value_type&gt;
 *   &lt;/calculation&gt;
 * &lt;/step&gt;
 * </pre>
 *
 * <p>Also handles the multi-calculation container format:
 * <pre>
 * &lt;calculations&gt;&lt;calculation&gt;…&lt;/calculation&gt;&lt;/calculations&gt;
 * </pre>
 * Only the first {@code <calculation>} element is mapped; multi-calculation KTRs
 * need a dedicated per-step approach and are out of scope for the current converter.
 *
 * <p>Emitted params (field names; KtrParser resolves them to 0-based indices):
 * <ul>
 *   <li>{@code fieldName}  — output field name to append</li>
 *   <li>{@code fieldA}     — name of the first operand field</li>
 *   <li>{@code fieldB}     — name of the second operand field (may be absent for unary ops)</li>
 *   <li>{@code operation}  — ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO</li>
 * </ul>
 */
public final class CalculatorMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Accept both <calculation> directly and <calculations><calculation>
        Element calc = firstCalcElement(e);
        if (calc == null) return p;

        put(p, "fieldName",  text(calc, "field_name"));
        put(p, "fieldA",     text(calc, "field_a"));
        put(p, "fieldB",     text(calc, "field_b"));
        put(p, "operation",  mapCalcType(text(calc, "calc_type")));

        return p;
    }

    private static Element firstCalcElement(Element step) {
        // Try <calculations><calculation> first
        NodeList calcContainers = step.getElementsByTagName("calculations");
        if (calcContainers.getLength() > 0) {
            NodeList inner = ((Element) calcContainers.item(0)).getElementsByTagName("calculation");
            if (inner.getLength() > 0) return (Element) inner.item(0);
        }
        // Fall back to bare <calculation>
        NodeList calcs = step.getElementsByTagName("calculation");
        return calcs.getLength() > 0 ? (Element) calcs.item(0) : null;
    }

    /**
     * Maps Pentaho calc_type values to CalculatorStep operation strings.
     * Unknown types are passed through unchanged.
     */
    private static String mapCalcType(String ct) {
        if (ct == null) return null;
        return switch (ct.toUpperCase()) {
            case "MULTIPLY",          "PRODUCT"     -> "MULTIPLY";
            case "DIVIDE",            "DIVIDE_INT"  -> "DIVIDE";
            case "ADD",               "PLUS"        -> "ADD";
            case "SUBTRACT",          "MINUS"       -> "SUBTRACT";
            case "MODULO",            "REMAINDER"   -> "MODULO";
            default                                  -> ct.toUpperCase();
        };
    }

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
