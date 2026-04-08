package com.pentaho.migration.step.impl.subtransform;

import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.model.TransformationDefinition;
import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.Step;
import com.pentaho.migration.step.StepRegistry;
import com.pentaho.migration.step.impl.sink.MappingOutputStep;
import com.pentaho.migration.step.impl.source.MappingInputStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Executes a sub-transformation (Mapping). Finds the MappingInput step,
 * feeds it the upstream rows, executes the sub-transformation, and returns
 * the rows from the MappingOutput step.
 * Equivalent to Pentaho's Mapping step.
 *
 * <p>Params:
 * <ul>
 *   <li>{@code transformationPath} — path to the sub-transformation YAML file
 * </ul>
 */
public class MappingStep implements Step {

    private String transformationPath;
    private StepRegistry registry;

    public MappingStep() {
        this.registry = StepRegistry.withDefaults();
    }

    public MappingStep(StepRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void configure(Map<String, String> params) {
        transformationPath = params.get("transformationPath");
    }

    @Override
    public Iterator<Row> apply(List<Iterator<Row>> inputs) throws Exception {
        Path yamlPath = Paths.get(transformationPath);
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        TransformationDefinition def = mapper.readValue(
                Files.readAllBytes(yamlPath), TransformationDefinition.class);

        // Feed upstream rows into MappingInput step via the registry
        Iterator<Row> upstream = inputs.isEmpty() ? Collections.emptyIterator() : inputs.get(0);
        TransformationExecutor executor = new TransformationExecutor(registry);
        return executor.executeMapping(def, upstream);
    }
}
