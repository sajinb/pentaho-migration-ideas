package com.pentaho.migration.converter.step;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps a Pentaho {@code Mapping (Sub-transformation)} step XML to our {@code Mapping} step params.
 *
 * <p>Three XML formats exist depending on the Pentaho version:
 *
 * <p><b>Format A</b> — flat filename (Pentaho 7.x and earlier):
 * <pre>
 * &lt;filename&gt;path/to/sub.ktr&lt;/filename&gt;
 * </pre>
 *
 * <p><b>Format B</b> — explicit specification method, filename-based (Pentaho 8.x/9.x):
 * <pre>
 * &lt;specification_method&gt;filename&lt;/specification_method&gt;
 * &lt;filename&gt;path/to/sub.ktr&lt;/filename&gt;
 * </pre>
 *
 * <p><b>Format C</b> — repository reference (Pentaho 8.x/9.x):
 * <pre>
 * &lt;specification_method&gt;rep_by_name&lt;/specification_method&gt;
 * &lt;trans_name&gt;my_transformation&lt;/trans_name&gt;
 * &lt;directory&gt;/transformations&lt;/directory&gt;
 * </pre>
 *
 * <p>Emitted params:
 * <ul>
 *   <li>{@code transformationPath} — path to the sub-transformation YAML (converted from .ktr path);
 *       for repository references: {@code directory/trans_name.yaml}</li>
 * </ul>
 */
public final class MappingMapper implements StepXmlMapper {

    @Override
    public Map<String, String> map(Element e) {
        Map<String, String> p = new HashMap<>();

        String filename = text(e, "filename");

        // Format C: repository reference — compose path from <directory> + <trans_name>
        if (filename == null || filename.isBlank()) {
            String dir    = text(e, "directory");
            String name   = text(e, "trans_name");
            if (name != null && !name.isBlank()) {
                String base = (dir != null && !dir.isBlank() && !"/".equals(dir))
                        ? dir.replaceAll("/$", "") + "/" + name
                        : name;
                filename = base + ".ktr"; // synthetic, will be converted below
            }
        }

        if (filename != null && !filename.isBlank()) {
            // Convert .ktr extension to .yaml; paths without .ktr get .yaml appended
            String yamlPath = filename.trim().replaceAll("\\.ktr$", ".yaml");
            if (!yamlPath.endsWith(".yaml")) yamlPath += ".yaml";
            p.put("transformationPath", yamlPath);
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
