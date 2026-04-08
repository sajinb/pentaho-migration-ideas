package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;

import java.util.Map;

/**
 * Maps a Pentaho KTR {@code <step>} XML element to the {@code params} map
 * expected by {@link com.pentaho.migration.step.Step#configure(Map)}.
 *
 * <p>Implementations are registered in {@link StepXmlMapperRegistry}.
 */
public interface StepXmlMapper {

    /**
     * Extract step parameters from the given {@code <step>} element.
     *
     * @param stepElement the {@code <step>} DOM element from a KTR document
     * @return a mutable map of parameter key → value strings (never null)
     */
    Map<String, String> map(Element stepElement);
}
