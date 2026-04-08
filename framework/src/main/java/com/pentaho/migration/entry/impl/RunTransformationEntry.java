package com.pentaho.migration.entry.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.entry.JobEntry;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.step.StepRegistry;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Executes a KTR transformation from a YAML file.
 * Equivalent to Pentaho's "Transformation" job entry.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code transformationPath} — path to the transformation YAML file
 * </ul>
 */
public class RunTransformationEntry implements JobEntry {

    private String transformationPath;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    @Override
    public void configure(Map<String, String> params) {
        transformationPath = params.get("transformationPath");
    }

    @Override
    public boolean execute(Map<String, String> context) throws Exception {
        TransformationDefinition def = mapper.readValue(
                Files.readAllBytes(Paths.get(transformationPath)),
                TransformationDefinition.class);

        new TransformationExecutor(StepRegistry.withDefaults()).execute(def);
        return true;
    }
}
