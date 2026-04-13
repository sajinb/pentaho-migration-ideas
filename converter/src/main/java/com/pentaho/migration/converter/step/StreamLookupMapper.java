package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.pentaho.migration.converter.step.XmlHelper.child;

/**
 * Maps Pentaho {@code StreamLookup} XML to params consumed by {@code StreamLookupStep}.
 *
 * <p>Pentaho XML structure:
 * <pre>
 * &lt;step&gt;
 *   &lt;name&gt;Enrich&lt;/name&gt;
 *   &lt;type&gt;StreamLookup&lt;/type&gt;
 *   &lt;from&gt;Lookup Source&lt;/from&gt;   &lt;!-- lookup stream step name --&gt;
 *   &lt;lookup&gt;
 *     &lt;key&gt;
 *       &lt;name&gt;order_id&lt;/name&gt;    &lt;!-- join field in main stream --&gt;
 *       &lt;field&gt;id&lt;/field&gt;         &lt;!-- join field in lookup stream --&gt;
 *     &lt;/key&gt;
 *     &lt;value&gt;
 *       &lt;name&gt;price&lt;/name&gt;        &lt;!-- field to copy from lookup row --&gt;
 *       &lt;rename&gt;unit_price&lt;/rename&gt; &lt;!-- output field name (optional) --&gt;
 *     &lt;/value&gt;
 *   &lt;/lookup&gt;
 * &lt;/step&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code lookupStep}   — lookup stream step name (used by KtrParser to infer input index)</li>
 *   <li>{@code keyStream}    — comma-separated join key field names in the main stream</li>
 *   <li>{@code keyLookup}    — comma-separated join key field names in the lookup stream</li>
 *   <li>{@code valueFields}  — comma-separated field names to copy from lookup rows</li>
 *   <li>{@code valueRenames} — comma-separated output names (parallel to valueFields)</li>
 * </ul>
 * KtrParser subsequently resolves the name lists to index lists
 * ({@code keyStreamCols}, {@code keyLookupCols}, {@code valueFieldCols}).
 */
public final class StreamLookupMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // Lookup source step: prefer <from>, fall back to <lookupsteps>/<lookupstep>/<name>
        String lookupStep = child(e, "from");
        if (lookupStep == null || lookupStep.isBlank()) {
            NodeList lsNodes = e.getElementsByTagName("lookupstep");
            if (lsNodes.getLength() > 0) {
                lookupStep = child((Element) lsNodes.item(0), "name");
            }
        }
        put(p, "lookupStep", lookupStep);

        NodeList lookupContainers = e.getElementsByTagName("lookup");
        if (lookupContainers.getLength() == 0) return p;
        Element lookup = (Element) lookupContainers.item(0);

        // Key fields
        List<String> keyStream = new ArrayList<>();
        List<String> keyLookup = new ArrayList<>();
        NodeList keyEls = lookup.getElementsByTagName("key");
        for (int i = 0; i < keyEls.getLength(); i++) {
            Element key = (Element) keyEls.item(i);
            String name  = child(key, "name");
            String field = child(key, "field");
            if (name  != null && !name.isBlank())  keyStream.add(name);
            if (field != null && !field.isBlank()) keyLookup.add(field);
        }
        if (!keyStream.isEmpty()) p.put("keyStream", String.join(",", keyStream));
        if (!keyLookup.isEmpty()) p.put("keyLookup", String.join(",", keyLookup));

        // Value fields to retrieve from lookup row
        List<String> valueFields  = new ArrayList<>();
        List<String> valueRenames = new ArrayList<>();
        NodeList valueEls = lookup.getElementsByTagName("value");
        for (int i = 0; i < valueEls.getLength(); i++) {
            Element val    = (Element) valueEls.item(i);
            String name    = child(val, "name");
            String rename  = child(val, "rename");
            if (name != null && !name.isBlank()) {
                valueFields.add(name);
                valueRenames.add(rename != null && !rename.isBlank() ? rename : name);
            }
        }
        if (!valueFields.isEmpty())  p.put("valueFields",  String.join(",", valueFields));
        if (!valueRenames.isEmpty()) p.put("valueRenames", String.join(",", valueRenames));

        return p;
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
