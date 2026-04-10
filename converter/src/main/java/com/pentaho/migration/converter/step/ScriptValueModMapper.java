package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Pentaho {@code ModifiedJavaScriptValue} (ScriptValueMod) XML to our
 * {@code ScriptValueMod} step params.
 *
 * <p>Pentaho XML formats:
 *
 * <p><b>Modern format</b> ({@code jsScripts / jsScript} container):
 * <pre>
 * &lt;jsScripts&gt;
 *   &lt;jsScript&gt;
 *     &lt;jsScript_type&gt;0&lt;/jsScript_type&gt;   &lt;!-- 0 = transform script --&gt;
 *     &lt;jsScript_name&gt;Script 1&lt;/jsScript_name&gt;
 *     &lt;jsScript_script&gt;&lt;![CDATA[
 *       var category = (sales &gt; 10000) ? "HIGH" : "LOW";
 *     ]]&gt;&lt;/jsScript_script&gt;
 *   &lt;/jsScript&gt;
 * &lt;/jsScripts&gt;
 * &lt;fields&gt;
 *   &lt;field&gt;&lt;name&gt;category&lt;/name&gt;&lt;rename/&gt;&lt;type&gt;String&lt;/type&gt;&lt;/field&gt;
 * &lt;/fields&gt;
 * </pre>
 *
 * <p><b>Legacy format</b> (bare {@code &lt;script&gt;} element):
 * <pre>
 * &lt;script&gt;&lt;![CDATA[
 *   var result = fieldA + fieldB;
 * ]]&gt;&lt;/script&gt;
 * &lt;fields&gt;
 *   &lt;field&gt;&lt;name&gt;result&lt;/name&gt;&lt;/field&gt;
 * &lt;/fields&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code script}       — JavaScript source code</li>
 *   <li>{@code outputFields} — comma-separated output field names</li>
 *   <li>{@code outputRename} — comma-separated rename targets (empty entry = keep original name)</li>
 * </ul>
 * ({@code fieldNames} is injected later by KtrParser from upstream schema.)
 */
public final class ScriptValueModMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // ── Script source ────────────────────────────────────────────────────
        String script = extractScript(e);
        if (script != null && !script.isBlank()) p.put("script", script.trim());

        // ── Output field definitions ─────────────────────────────────────────
        List<String> names   = new ArrayList<>();
        List<String> renames = new ArrayList<>();

        NodeList fieldsEls = e.getElementsByTagName("fields");
        if (fieldsEls.getLength() > 0) {
            // Use the LAST <fields> element — Pentaho sometimes has a <fields> inside
            // <jsScripts>, so we prefer the outermost one that holds output column defs.
            Element fieldsEl = findOutputFieldsElement(e);
            if (fieldsEl != null) {
                NodeList fieldList = fieldsEl.getElementsByTagName("field");
                for (int i = 0; i < fieldList.getLength(); i++) {
                    Element f = (Element) fieldList.item(i);
                    String name   = text(f, "name");
                    String rename = text(f, "rename");
                    if (name != null && !name.isBlank()) {
                        names.add(name.trim());
                        renames.add(rename != null ? rename.trim() : "");
                    }
                }
            }
        }

        if (!names.isEmpty())   p.put("outputFields", String.join(",", names));
        if (!renames.isEmpty()) p.put("outputRename",  String.join(",", renames));

        return p;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Extracts the JavaScript source. Tries:
     * <ol>
     *   <li>{@code <jsScripts><jsScript><jsScript_script>} (modern format, type=0 preferred)</li>
     *   <li>{@code <script>} bare element (legacy format)</li>
     * </ol>
     */
    private static String extractScript(Element step) {
        NodeList jsScriptsEls = step.getElementsByTagName("jsScripts");
        if (jsScriptsEls.getLength() > 0) {
            Element jsScripts = (Element) jsScriptsEls.item(0);
            NodeList scripts  = jsScripts.getElementsByTagName("jsScript");

            // Prefer the first transform script (type 0); fall back to first available
            String fallback = null;
            for (int i = 0; i < scripts.getLength(); i++) {
                Element s    = (Element) scripts.item(i);
                String  type = text(s, "jsScript_type");
                String  src  = text(s, "jsScript_script");
                if (src == null || src.isBlank()) continue;
                if ("0".equals(type)) return src;
                if (fallback == null) fallback = src;
            }
            if (fallback != null) return fallback;
        }

        // Legacy bare <script>
        NodeList scriptEls = step.getElementsByTagName("script");
        if (scriptEls.getLength() > 0) {
            return scriptEls.item(0).getTextContent();
        }
        return null;
    }

    /**
     * Returns the {@code <fields>} element that directly contains output field definitions
     * (i.e. the direct child of the step element, not the one nested inside jsScripts).
     */
    private static Element findOutputFieldsElement(Element step) {
        NodeList children = step.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child
                    && "fields".equals(child.getLocalName() != null
                            ? child.getLocalName() : child.getNodeName())) {
                return child;
            }
        }
        // Fallback: first <fields> anywhere in the step (works for legacy format)
        NodeList all = step.getElementsByTagName("fields");
        return all.getLength() > 0 ? (Element) all.item(0) : null;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String t = nodes.item(0).getTextContent();
        return t == null ? null : t.trim();
    }
}
