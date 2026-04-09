package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Pentaho {@code Formula} XML to our {@code Formula} step params.
 *
 * <p>Pentaho XML format:
 * <pre>
 * &lt;step&gt;
 *   &lt;type&gt;Formula&lt;/type&gt;
 *   &lt;formula&gt;
 *     &lt;field_name&gt;risk_level&lt;/field_name&gt;
 *     &lt;formula&gt;IF([ltv_total]&gt;10000;"CRITICAL";"NORMAL")&lt;/formula&gt;
 *   &lt;/formula&gt;
 * &lt;/step&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code fieldName}     — output column name</li>
 *   <li>{@code formulaString} — OpenFormula expression string</li>
 *   <li>{@code fieldNames}    — injected by KtrParser: upstream field names for [name] resolution</li>
 * </ul>
 */
public final class FormulaMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Pentaho wraps both inside a <formula> container element.
        // Use direct child search to avoid ambiguity with nested <formula> elements.
        NodeList formulaContainers = e.getElementsByTagName("formula");
        if (formulaContainers.getLength() > 0) {
            Element container = (Element) formulaContainers.item(0);

            String fieldName = text(container, "field_name");
            if (fieldName != null && !fieldName.isBlank()) {
                p.put("fieldName", fieldName);
            }

            // The <formula> tag inside the container holds the expression string.
            // Since getElementsByTagName is recursive, item(0) on the outer element is the
            // container itself; the inner <formula> is item(1) if present — but safer to
            // look for it inside the container.
            NodeList innerFormulas = container.getElementsByTagName("formula");
            if (innerFormulas.getLength() > 0) {
                String expr = innerFormulas.item(0).getTextContent();
                if (expr != null && !expr.isBlank()) p.put("formulaString", expr.trim());
            }
        }

        return p;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String t = nodes.item(0).getTextContent();
        return t == null ? null : t.trim();
    }
}
