package com.pentaho.migration.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.pentaho.migration.model.JobDefinition;
import com.pentaho.migration.model.TransformationDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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
     * @param inputZip  path to the input zip
     * @param outputZip path where the converted zip will be written (created or overwritten)
     */
    public void convert(Path inputZip, Path outputZip) throws Exception {
        try (ZipInputStream  zin  = new ZipInputStream(Files.newInputStream(inputZip));
             ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(outputZip))) {

            Set<String> usedNames = new HashSet<>();
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory()) {
                    zout.putNextEntry(new ZipEntry(name));
                    zout.closeEntry();
                    continue;
                }

                byte[] convertedBytes = null;
                String outName = null;

                if (name.endsWith(".ktr")) {
                    convertedBytes = convertKtrToBytes(zin);
                    outName = replaceExtension(name, ".yaml");
                } else if (name.endsWith(".kjb")) {
                    convertedBytes = convertKjbToBytes(zin);
                    outName = replaceExtension(name, ".yaml");
                }
                // Skip files that are neither .ktr nor .kjb

                if (convertedBytes != null) {
                    String uniqueName = makeUnique(outName, usedNames);
                    zout.putNextEntry(new ZipEntry(uniqueName));
                    zout.write(convertedBytes);
                    zout.closeEntry();
                }
                zin.closeEntry();
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
}
