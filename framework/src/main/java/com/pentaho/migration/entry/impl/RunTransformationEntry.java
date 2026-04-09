package com.pentaho.migration.entry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.entry.JobEntry;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.step.StepRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Executes a KTR transformation from a YAML file.
 * Equivalent to Pentaho's "Transformation" job entry.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code transformationPath} — path to the transformation YAML file (absolute or
 *       relative; relative paths are resolved against {@code basePath} from the context)
 * </ul>
 *
 * <p>Context keys (read-only):
 * <ul>
 *   <li>{@code basePath} — directory that holds the YAML files; set by the executor
 * </ul>
 */
public class RunTransformationEntry implements JobEntry {

    private String transformationPath;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Override
    public void configure(Map<String, String> params) {
        transformationPath = params.get("transformationPath");
        if (transformationPath == null || transformationPath.isBlank())
            throw new IllegalArgumentException("RunTransformation entry is missing 'transformationPath'");
    }

    @Override
    public boolean execute(Map<String, String> context) throws Exception {
        Path yamlPath = resolvedPath(context);
        if (!Files.exists(yamlPath)) {
            // Provide a helpful listing of what IS in the base directory
            String available = availableYamlFiles(context);
            throw new IllegalStateException(
                    "Transformation YAML not found: " + yamlPath.toAbsolutePath() +
                    " (transformationPath='" + transformationPath + "')" +
                    (available.isEmpty() ? "" : ". Available YAML files: " + available));
        }

        TransformationDefinition def;
        try {
            def = mapper.readValue(yamlPath.toFile(), TransformationDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to parse transformation YAML '" + yamlPath.toAbsolutePath() + "': " + e.getMessage(), e);
        }

        try {
            new TransformationExecutor(StepRegistry.withDefaults()).execute(def);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Transformation '" + transformationPath + "' failed: " + e.getMessage(), e);
        }
        return true;
    }

    private Path resolvedPath(Map<String, String> context) {
        String base = context.get("basePath");
        if (base == null || base.isBlank()) {
            // No basePath: use transformationPath as-is (useful in unit tests)
            return Paths.get(transformationPath);
        }
        // With basePath: strip directory components from transformationPath so that
        // Pentaho absolute paths (e.g. C:\jobs\transform.yaml) resolve to just the
        // filename inside basePath.  Handles both '/' and '\' separators.
        String filename = transformationPath;
        int lastSep = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        if (lastSep >= 0) filename = filename.substring(lastSep + 1);
        return Paths.get(base).resolve(filename);
    }

    private String availableYamlFiles(Map<String, String> context) {
        String base = context.get("basePath");
        if (base == null || base.isBlank()) return "";
        try {
            return Files.list(Paths.get(base))
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".yaml"))
                    .sorted()
                    .collect(Collectors.joining(", "));
        } catch (IOException e) {
            return "(could not list directory: " + e.getMessage() + ")";
        }
    }
}
