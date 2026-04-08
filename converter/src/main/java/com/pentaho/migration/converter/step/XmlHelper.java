package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Shared DOM utility helpers used by step mappers.
 */
final class XmlHelper {

    private XmlHelper() {}

    /** Returns trimmed text content of the first child element with {@code localName}, or {@code null}. */
    static String child(Element parent, String localName) {
        NodeList nodes = parent.getElementsByTagName(localName);
        if (nodes.getLength() == 0) return null;
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /** Returns trimmed text content of the first child element, or {@code defaultValue}. */
    static String child(Element parent, String localName, String defaultValue) {
        String v = child(parent, localName);
        return (v == null || v.isEmpty()) ? defaultValue : v;
    }

    /** Converts Pentaho Y/N flag to "true"/"false". */
    static String yesNo(String value) {
        return "Y".equalsIgnoreCase(value) ? "true" : "false";
    }

    /** Iterates direct child Element nodes of parent. */
    static Iterable<Element> directChildElements(Element parent) {
        return () -> new java.util.Iterator<>() {
            private final NodeList children = parent.getChildNodes();
            private int index = 0;
            private Element next = advance();

            private Element advance() {
                while (index < children.getLength()) {
                    Node n = children.item(index++);
                    if (n.getNodeType() == Node.ELEMENT_NODE) return (Element) n;
                }
                return null;
            }

            @Override public boolean hasNext() { return next != null; }
            @Override public Element next() { Element e = next; next = advance(); return e; }
        };
    }
}
