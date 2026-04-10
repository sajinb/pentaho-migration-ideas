package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps Pentaho {@code GroupBy} XML to our {@code GroupBy} step params.
 *
 * <p>Pentaho XML format:
 * <pre>
 * &lt;step&gt;
 *   &lt;type&gt;GroupBy&lt;/type&gt;
 *   &lt;group&gt;
 *     &lt;field&gt;&lt;name&gt;customer_id&lt;/name&gt;&lt;/field&gt;
 *   &lt;/group&gt;
 *   &lt;fields&gt;
 *     &lt;field&gt;
 *       &lt;aggregate&gt;ltv_total&lt;/aggregate&gt;  &lt;!-- output column name --&gt;
 *       &lt;subject&gt;amount&lt;/subject&gt;          &lt;!-- input column to aggregate --&gt;
 *       &lt;type&gt;SUM&lt;/type&gt;
 *     &lt;/field&gt;
 *   &lt;/fields&gt;
 * &lt;/step&gt;
 * </pre>
 *
 * <p>Emitted params (column names; KtrParser resolves them to 0-based indices):
 * <ul>
 *   <li>{@code groupColumns} — comma-separated group-by column names</li>
 *   <li>{@code aggColumns}   — comma-separated subject column names to aggregate</li>
 *   <li>{@code aggFunctions} — comma-separated aggregate functions (SUM, COUNT, …)</li>
 *   <li>{@code aggNames}     — comma-separated output column names for aggregates</li>
 * </ul>
 */
public final class GroupByMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        // ── Group key columns ────────────────────────────────────────────────
        List<String> groupCols = new ArrayList<>();
        NodeList groupEls = e.getElementsByTagName("group");
        if (groupEls.getLength() > 0) {
            Element group = (Element) groupEls.item(0);
            NodeList fields = group.getElementsByTagName("field");
            for (int i = 0; i < fields.getLength(); i++) {
                String name = text((Element) fields.item(i), "name");
                if (name != null && !name.isBlank()) groupCols.add(name);
            }
        }
        if (!groupCols.isEmpty()) p.put("groupColumns", String.join(",", groupCols));

        // ── Aggregate definitions ─────────────────────────────────────────────
        // Two formats exist depending on Pentaho version:
        //   Old: <fields><field><aggregate>out_name</aggregate><subject>col</subject><type>SUM</type></field></fields>
        //   New: <aggregates><aggregate><name>out_name</name><subject>col</subject><type>SUM</type></aggregate></aggregates>
        List<String> aggCols  = new ArrayList<>();
        List<String> aggFns   = new ArrayList<>();
        List<String> aggNames = new ArrayList<>();

        NodeList fieldsEls = e.getElementsByTagName("fields");
        if (fieldsEls.getLength() > 0) {
            // Old format
            Element fields = (Element) fieldsEls.item(0);
            NodeList fieldEls = fields.getElementsByTagName("field");
            for (int i = 0; i < fieldEls.getLength(); i++) {
                Element f = (Element) fieldEls.item(i);
                String aggName = text(f, "aggregate");
                String subject = text(f, "subject");
                String fn      = text(f, "type");
                if (subject != null && !subject.isBlank()) aggCols.add(subject);
                if (fn      != null && !fn.isBlank())      aggFns.add(fn.toUpperCase());
                if (aggName != null && !aggName.isBlank()) aggNames.add(aggName);
            }
        } else {
            // New format: <aggregates>/<aggregate>
            NodeList aggContainers = e.getElementsByTagName("aggregates");
            if (aggContainers.getLength() > 0) {
                Element aggs = (Element) aggContainers.item(0);
                NodeList aggEls = aggs.getElementsByTagName("aggregate");
                for (int i = 0; i < aggEls.getLength(); i++) {
                    Element agg = (Element) aggEls.item(i);
                    String aggName = text(agg, "name");
                    String subject = text(agg, "subject");
                    String fn      = text(agg, "type");
                    if (subject != null && !subject.isBlank()) aggCols.add(subject);
                    if (fn      != null && !fn.isBlank())      aggFns.add(fn.toUpperCase());
                    if (aggName != null && !aggName.isBlank()) aggNames.add(aggName);
                }
            }
        }
        if (!aggCols.isEmpty())  p.put("aggColumns",   String.join(",", aggCols));
        if (!aggFns.isEmpty())   p.put("aggFunctions", String.join(",", aggFns));
        if (!aggNames.isEmpty()) p.put("aggNames",     String.join(",", aggNames));

        return p;
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        if (nodes.getLength() == 0) return null;
        String t = nodes.item(0).getTextContent();
        return t == null ? null : t.trim();
    }
}
