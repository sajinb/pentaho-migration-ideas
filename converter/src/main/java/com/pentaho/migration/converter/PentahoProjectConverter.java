package com.pentaho.migration.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.pentaho.migration.model.EntryDefinition;
import com.pentaho.migration.model.JobDefinition;
import com.pentaho.migration.model.TransformationDefinition;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Top-level API for converting Pentaho KJB/KTR XML files to YAML job definitions.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * PentahoProjectConverter converter = new PentahoProjectConverter();
 *
 * // Convert a project zip (1 KJB + N KTRs) → zip of YAML files
 * converter.convert(inputZipPath, outputZipPath);
 *
 * // Convert a single KTR stream to YAML string
 * String yaml = converter.convertKtr(new FileInputStream("transform.ktr"));
 * }</pre>
 *
 * <h3>Zip contract</h3>
 * <ul>
 *   <li>Input zip contains {@code .ktr} and {@code .kjb} files (any directory depth).</li>
 *   <li>Output zip mirrors the input structure with {@code .yaml} extensions.</li>
 * </ul>
 */
public final class PentahoProjectConverter {

    private final KtrParser ktrParser;
    private final KjbParser kjbParser;
    private final ObjectMapper yaml;

    public PentahoProjectConverter() {
        this(new KtrParser(), new KjbParser());
    }

    public PentahoProjectConverter(KtrParser ktrParser, KjbParser kjbParser) {
        this.ktrParser = ktrParser;
        this.kjbParser = kjbParser;
        YAMLFactory yamlFactory = YAMLFactory.builder()
                .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                .build();
        this.yaml = new ObjectMapper(yamlFactory);
        this.yaml.findAndRegisterModules(); // registers JavaTimeModule etc.
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Converts a zip file containing {@code .ktr} and {@code .kjb} files into a zip
     * of equivalent {@code .yaml} files.
     *
     * <p>KTR files are processed first so they always get the base output name
     * (e.g. {@code report.yaml}). KJB files are processed second; if a KJB shares a
     * base name with a KTR the KJB output is renamed (e.g. {@code report_2.yaml}).
     * Any {@code transformationPath} references inside KJB output YAML are
     * automatically updated to reflect the actual assigned KTR name.
     *
     * @param inputZip  path to the input zip
     * @param outputZip path where the converted zip will be written (created or overwritten)
     */
    public void convert(Path inputZip, Path outputZip) throws Exception {

        // ── Pass 1: read all entries into memory, split by type ──────────────
        Map<String, byte[]> ktrEntries = new LinkedHashMap<>();
        Map<String, byte[]> kjbEntries = new LinkedHashMap<>();

        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(inputZip))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    String name = entry.getName();
                    byte[] bytes = zin.readAllBytes();
                    if      (name.endsWith(".ktr")) ktrEntries.put(name, bytes);
                    else if (name.endsWith(".kjb")) kjbEntries.put(name, bytes);
                }
                zin.closeEntry();
            }
        }

        Set<String> usedNames = new HashSet<>();

        // ── Pass 2: convert KTRs first → they own the base names ─────────────
        // ktrRenameMap: expected yaml name → actual assigned name
        // (identical unless there was a collision)
        Map<String, String> ktrRenameMap = new LinkedHashMap<>();
        Map<String, byte[]> ktrOutput    = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> e : ktrEntries.entrySet()) {
            String expected = replaceExtension(e.getKey(), ".yaml");
            String assigned = makeUnique(expected, usedNames);
            ktrRenameMap.put(expected, assigned);
            ktrOutput.put(assigned,
                    convertKtrToBytes(new ByteArrayInputStream(e.getValue())));
        }

        // ── Pass 3: convert KJBs second → remap transformationPath if renamed ─
        Map<String, byte[]> kjbOutput = new LinkedHashMap<>();

        for (Map.Entry<String, byte[]> e : kjbEntries.entrySet()) {
            String expected = replaceExtension(e.getKey(), ".yaml");
            String assigned = makeUnique(expected, usedNames);
            byte[] converted = convertKjbToBytes(new ByteArrayInputStream(e.getValue()));
            converted = remapTransformationPaths(converted, ktrRenameMap);
            kjbOutput.put(assigned, converted);
        }

        // ── Write output zip (KTRs first, then KJBs) ─────────────────────────
        try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(outputZip))) {
            for (Map.Entry<String, byte[]> e : ktrOutput.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : kjbOutput.entrySet()) {
                zout.putNextEntry(new ZipEntry(e.getKey()));
                zout.write(e.getValue());
                zout.closeEntry();
            }
        }
    }

    /**
     * Converts a single KTR XML stream to a YAML string representing
     * a {@link TransformationDefinition}.
     */
    public String convertKtr(InputStream ktrXml) throws Exception {
        TransformationDefinition def = ktrParser.parse(ktrXml);
        return yaml.writeValueAsString(def);
    }

    /**
     * Converts a single KJB XML stream to a YAML string representing
     * a {@link JobDefinition}.
     */
    public String convertKjb(InputStream kjbXml) throws Exception {
        JobDefinition def = kjbParser.parse(kjbXml);
        return yaml.writeValueAsString(def);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private byte[] convertKtrToBytes(InputStream in) throws Exception {
        TransformationDefinition def = ktrParser.parse(nonClosingWrapper(in));
        return yaml.writeValueAsBytes(def);
    }

    private byte[] convertKjbToBytes(InputStream in) throws Exception {
        JobDefinition def = kjbParser.parse(nonClosingWrapper(in));
        return yaml.writeValueAsBytes(def);
    }

    /**
     * Wraps a ZipInputStream so that closing the wrapper does NOT close the underlying stream
     * (ZipInputStream must remain open for further entries).
     */
    private static InputStream nonClosingWrapper(InputStream delegate) {
        return new InputStream() {
            @Override public int read()               throws IOException { return delegate.read(); }
            @Override public int read(byte[] b, int o, int l) throws IOException { return delegate.read(b, o, l); }
            @Override public void close() { /* intentionally no-op */ }
        };
    }

    /**
     * Returns {@code name} if it hasn't been used yet, otherwise appends {@code _2}, {@code _3}, …
     * until a unique name is found. Records the chosen name in {@code used}.
     */
    private static String makeUnique(String name, Set<String> used) {
        if (used.add(name)) return name;
        int dot  = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext  = dot < 0 ? ""   : name.substring(dot);
        int counter = 2;
        String candidate;
        do { candidate = base + "_" + counter++ + ext; } while (!used.add(candidate));
        return candidate;
    }

    private static String replaceExtension(String path, String newExt) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path + newExt : path.substring(0, dot) + newExt;
    }

    /**
     * Rewrites {@code transformationPath} values in a serialized KJB YAML according to
     * {@code renameMap} (expected YAML name → actual assigned name).
     * Only rewrites entries that were actually renamed (i.e. where expected ≠ assigned).
     * Returns the original bytes unchanged if no remapping is needed.
     */
    private byte[] remapTransformationPaths(byte[] yamlBytes,
                                             Map<String, String> renameMap) throws Exception {
        // Fast path: if no renames happened, nothing to do
        boolean anyRenamed = renameMap.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(e.getValue()));
        if (!anyRenamed) return yamlBytes;

        JobDefinition def = yaml.readValue(yamlBytes, JobDefinition.class);
        boolean changed = false;
        if (def.entries != null) {
            for (EntryDefinition entry : def.entries) {
                if (entry.params == null) continue;
                String tp = entry.params.get("transformationPath");
                if (tp == null) continue;
                String remapped = renameMap.get(tp);
                if (remapped != null && !remapped.equals(tp)) {
                    entry.params.put("transformationPath", remapped);
                    changed = true;
                }
            }
        }
        return changed ? yaml.writeValueAsBytes(def) : yamlBytes;
    }
}
