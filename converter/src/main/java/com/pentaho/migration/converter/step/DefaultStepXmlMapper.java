package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Generic fallback mapper: copies every direct child text element of {@code <step>}
 * to the params map, skipping structural/GUI elements.
 *
 * <p>Used for step types that have no dedicated {@link StepXmlMapper}.
 * The resulting params keys match Pentaho's XML element names directly —
 * step implementations must use the same key names or override with a dedicated mapper.
 */
public final class DefaultStepXmlMapper implements StepXmlMapper {

    /** Elements that describe step structure/position, not parameters. */
    private static final Set<String> STRUCTURAL = Set.of(
            "name", "type", "description", "distribute", "custom_distribution",
            "copies", "partitioning", "GUI", "cluster_schema", "remotesteps",
            "gui", "xloc", "yloc", "draw"
    );

    @Override
    public Map<String, String> map(Element stepElement) {
        Map<String, String> params = new HashMap<>();
        for (Element child : XmlHelper.directChildElements(stepElement)) {
            String tag = child.getLocalName() != null ? child.getLocalName() : child.getTagName();
            if (STRUCTURAL.contains(tag)) continue;
            // Only include leaf text nodes (skip container elements with child elements)
            if (!child.hasChildNodes()) continue;
            String text = child.getTextContent();
            if (text != null && !text.isBlank()) {
                params.put(tag, text.trim());
            }
        }
        return params;
    }
}
