package com.pentaho.migration.entry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.entry.JobEntry;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.step.StepRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
            throw new IllegalStateException(
                    "Transformation YAML not found: " + yamlPath.toAbsolutePath() +
                    " (transformationPath='" + transformationPath + "')");
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
        Path p = Paths.get(transformationPath);
        if (p.isAbsolute()) return p;
        String base = context.get("basePath");
        if (base != null && !base.isBlank()) return Paths.get(base).resolve(transformationPath);
        return p; // relative to CWD — will produce a clear "not found" error
    }
}
