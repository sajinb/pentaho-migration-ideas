package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Pentaho {@code Calculator} XML to our {@code Calculator} step params.
 *
 * <p>Two XML formats exist depending on the KTR version:
 *
 * <p><b>Format A</b> (field-based second operand):
 * <pre>
 * &lt;calculation&gt;
 *   &lt;field_name&gt;tax_amount&lt;/field_name&gt;   &lt;!-- output field --&gt;
 *   &lt;calc_type&gt;MULTIPLY&lt;/calc_type&gt;
 *   &lt;field_a&gt;sale_price&lt;/field_a&gt;           &lt;!-- input field A --&gt;
 *   &lt;field_b&gt;tax_rate&lt;/field_b&gt;             &lt;!-- input field B --&gt;
 * &lt;/calculation&gt;
 * </pre>
 *
 * <p><b>Format B</b> (constant second operand):
 * <pre>
 * &lt;calculations&gt;&lt;calculation&gt;
 *   &lt;newfieldname&gt;score_double&lt;/newfieldname&gt;  &lt;!-- output field --&gt;
 *   &lt;conversion&gt;Multiply&lt;/conversion&gt;
 *   &lt;fieldname&gt;score&lt;/fieldname&gt;               &lt;!-- input field A --&gt;
 *   &lt;value&gt;2&lt;/value&gt;                            &lt;!-- constant operand B --&gt;
 * &lt;/calculation&gt;&lt;/calculations&gt;
 * </pre>
 *
 * <p>Only the first {@code <calculation>} element is mapped.
 *
 * <p>Emitted params (field names; KtrParser resolves them to 0-based indices):
 * <ul>
 *   <li>{@code fieldName}  — output field name to append</li>
 *   <li>{@code fieldA}     — name of the first operand field</li>
 *   <li>{@code fieldB}     — name of the second operand field (<em>or absent</em>)</li>
 *   <li>{@code valueB}     — literal constant for second operand (when {@code fieldB} is absent)</li>
 *   <li>{@code operation}  — ADD, SUBTRACT, MULTIPLY, DIVIDE, MODULO</li>
 * </ul>
 */
public final class CalculatorMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        Element calc = firstCalcElement(e);
        if (calc == null) return p;

        // Output field name: <field_name> (Format A) or <newfieldname> (Format B)
        String fieldName = text(calc, "field_name");
        if (fieldName == null) fieldName = text(calc, "newfieldname");
        put(p, "fieldName", fieldName);

        // First operand: <field_a> (Format A) or <fieldname> (Format B)
        String fieldA = text(calc, "field_a");
        if (fieldA == null) fieldA = text(calc, "fieldname");
        put(p, "fieldA", fieldA);

        // Second operand: field reference <field_b> or constant <value>
        String fieldB = text(calc, "field_b");
        if (fieldB != null && !fieldB.isBlank()) {
            put(p, "fieldB", fieldB);
        } else {
            // Constant value — CalculatorStep uses valueB instead of colB
            put(p, "valueB", text(calc, "value"));
        }

        // Operation: <calc_type> (Format A) or <conversion> (Format B)
        String calcType = text(calc, "calc_type");
        if (calcType == null) calcType = text(calc, "conversion");
        put(p, "operation", mapCalcType(calcType));

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

    private static String mapCalcType(String ct) {
        if (ct == null) return null;
        return switch (ct.toUpperCase()) {
            case "MULTIPLY", "PRODUCT"    -> "MULTIPLY";
            case "DIVIDE",   "DIVIDE_INT" -> "DIVIDE";
            case "ADD",      "PLUS"       -> "ADD";
            case "SUBTRACT", "MINUS"      -> "SUBTRACT";
            case "MODULO",   "REMAINDER"  -> "MODULO";
            default                        -> ct.toUpperCase();
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
