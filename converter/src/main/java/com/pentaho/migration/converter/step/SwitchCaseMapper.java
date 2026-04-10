package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps Pentaho {@code SwitchCase} XML to our {@code SwitchCase} step params.
 *
 * <p>Pentaho XML format:
 * <pre>
 * &lt;field_name&gt;status&lt;/field_name&gt;
 * &lt;case&gt;
 *   &lt;value&gt;New&lt;/value&gt;
 *   &lt;target&gt;New Status Output&lt;/target&gt;
 * &lt;/case&gt;
 * &lt;case&gt;
 *   &lt;value&gt;In Progress&lt;/value&gt;
 *   &lt;target&gt;In Progress Output&lt;/target&gt;
 * &lt;/case&gt;
 * &lt;!-- default case: empty value or absent &lt;value&gt; --&gt;
 * &lt;case&gt;
 *   &lt;value/&gt;
 *   &lt;target&gt;Default Output&lt;/target&gt;
 * &lt;/case&gt;
 * </pre>
 *
 * <p>Also handles the alternate format where the switch field is in
 * {@code <switch_column>} and cases are in a {@code <cases>} container.
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code field_name}       — name of field to switch on (resolved to {@code column}
 *                                   index by KtrParser)</li>
 *   <li>{@code case.VALUE}       — target step name for rows where field == VALUE;
 *                                   one entry per non-default case</li>
 *   <li>{@code defaultStep}      — target step name for rows matching no case
 *                                   (case with empty/absent {@code <value>})</li>
 * </ul>
 */
public final class SwitchCaseMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Field to switch on: <field_name> or <switch_column>
        String fieldName = text(e, "field_name");
        if (fieldName == null) fieldName = text(e, "switch_column");
        if (fieldName != null && !fieldName.isBlank()) p.put("field_name", fieldName.trim());

        // Cases: either direct <case> children of step, or inside a <cases> container
        NodeList casesContainers = e.getElementsByTagName("cases");
        Element source = (casesContainers.getLength() > 0)
                ? (Element) casesContainers.item(0)
                : e;

        NodeList caseEls = source.getElementsByTagName("case");
        for (int i = 0; i < caseEls.getLength(); i++) {
            Element caseEl = (Element) caseEls.item(i);

            String value  = text(caseEl, "value");
            String target = text(caseEl, "target");

            if (target == null || target.isBlank()) continue; // no target → skip

            if (value == null || value.isBlank()) {
                // Empty/absent <value> → default (catch-all) case
                p.put("defaultStep", target.trim());
            } else {
                // Normal case: key = "case.VALUE"
                p.put("case." + value.trim(), target.trim());
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
