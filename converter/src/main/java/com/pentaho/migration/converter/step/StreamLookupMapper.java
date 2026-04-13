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
 * <p>Handles two Pentaho KTR export formats:
 *
 * <p><b>Format A</b> (newer KTR / designer-generated):
 * <pre>
 * &lt;lookup&gt;
 *   &lt;key&gt;&lt;name&gt;order_id&lt;/name&gt;&lt;field&gt;id&lt;/field&gt;&lt;/key&gt;
 *   &lt;value&gt;&lt;name&gt;price&lt;/name&gt;&lt;rename&gt;unit_price&lt;/rename&gt;&lt;/value&gt;
 * &lt;/lookup&gt;
 * </pre>
 *
 * <p><b>Format B</b> (older / hand-authored KTR):
 * <pre>
 * &lt;keystream&gt;order_id&lt;/keystream&gt;   &lt;!-- join key in main stream --&gt;
 * &lt;keylookup&gt;id&lt;/keylookup&gt;         &lt;!-- join key in lookup stream --&gt;
 * &lt;valuestream&gt;
 *   &lt;value&gt;price&lt;/value&gt;            &lt;!-- source field in lookup stream --&gt;
 *   &lt;valuename&gt;unit_price&lt;/valuename&gt; &lt;!-- output field name (rename) --&gt;
 * &lt;/valuestream&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code lookupStep}   — lookup stream step name</li>
 *   <li>{@code keyStream}    — comma-separated join key field names in the main stream</li>
 *   <li>{@code keyLookup}    — comma-separated join key field names in the lookup stream</li>
 *   <li>{@code valueFields}  — comma-separated source field names in the lookup stream</li>
 *   <li>{@code valueRenames} — comma-separated output names (parallel to valueFields)</li>
 * </ul>
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

        // ── Format A: <lookup> container ─────────────────────────────────────
        NodeList lookupContainers = e.getElementsByTagName("lookup");
        if (lookupContainers.getLength() > 0) {
            Element lookup = (Element) lookupContainers.item(0);
            parseFormatA(lookup, p);
            return p;
        }

        // ── Format B: flat <keystream>, <keylookup>, <valuestream> ───────────
        parseFormatB(e, p);
        return p;
    }

    /** Format A: key/value inside a {@code <lookup>} container. */
    private static void parseFormatA(Element lookup, Map<String, String> p) {
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

        List<String> valueFields  = new ArrayList<>();
        List<String> valueRenames = new ArrayList<>();
        NodeList valueEls = lookup.getElementsByTagName("value");
        for (int i = 0; i < valueEls.getLength(); i++) {
            Element val   = (Element) valueEls.item(i);
            String name   = child(val, "name");
            String rename = child(val, "rename");
            if (name != null && !name.isBlank()) {
                valueFields.add(name);
                valueRenames.add(rename != null && !rename.isBlank() ? rename : name);
            }
        }
        if (!valueFields.isEmpty())  p.put("valueFields",  String.join(",", valueFields));
        if (!valueRenames.isEmpty()) p.put("valueRenames", String.join(",", valueRenames));
    }

    /**
     * Format B: flat {@code <keystream>}, {@code <keylookup>}, {@code <valuestream>} siblings.
     *
     * <p>Inside {@code <valuestream>}:
     * <ul>
     *   <li>{@code <value>}     — source field name in the lookup stream</li>
     *   <li>{@code <valuename>} — output (renamed) field name</li>
     * </ul>
     */
    private static void parseFormatB(Element e, Map<String, String> p) {
        // Keys: may be single values or comma-separated multi-key strings
        String ks = child(e, "keystream");
        String kl = child(e, "keylookup");
        put(p, "keyStream", ks);
        put(p, "keyLookup", kl);

        List<String> valueFields  = new ArrayList<>();
        List<String> valueRenames = new ArrayList<>();
        NodeList vstNodes = e.getElementsByTagName("valuestream");
        for (int i = 0; i < vstNodes.getLength(); i++) {
            Element vs     = (Element) vstNodes.item(i);
            String source  = child(vs, "value");      // source field name in lookup
            String rename  = child(vs, "valuename");  // output field name
            if (source != null && !source.isBlank()) {
                valueFields.add(source);
                valueRenames.add(rename != null && !rename.isBlank() ? rename : source);
            }
        }
        if (!valueFields.isEmpty())  p.put("valueFields",  String.join(",", valueFields));
        if (!valueRenames.isEmpty()) p.put("valueRenames", String.join(",", valueRenames));
    }

    private static void put(Map<String, String> map, String key, String value) {
        if (value != null && !value.isBlank()) map.put(key, value);
    }
}
