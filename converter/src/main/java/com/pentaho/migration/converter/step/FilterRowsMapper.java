package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.*;

/**
 * Maps Pentaho {@code FilterRows} XML to our {@code FilterRows} step params.
 *
 * <p>Pentaho stores the condition inside {@code <compare>} (older format) or
 * {@code <condition>} (newer format). We support both.
 */
public final class FilterRowsMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // send_true_to / send_false_to — target step names
        put(p, "trueStep",  child(e, "send_true_to"));
        put(p, "falseStep", child(e, "send_false_to"));

        // Try <compare> block first (older format)
        NodeList compareNodes = e.getElementsByTagName("compare");
        if (compareNodes.getLength() > 0) {
            Element cmp = (Element) compareNodes.item(0);
            put(p, "column", child(cmp, "fieldname"));
            put(p, "value",  child(cmp, "value"));
            return p;
        }

        // Try <condition>/<conditions>/<condition> (newer nested format)
        NodeList condNodes = e.getElementsByTagName("condition");
        if (condNodes.getLength() > 0) {
            Element cond = (Element) condNodes.item(0);
            put(p, "column", child(cond, "leftvalue"));
            put(p, "value",  child(cond, "rightstring"));
        }

        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
