package com.pentaho.migration.model;

import java.util.List;
import java.util.Map;

public class TransformationDefinition {
    public String name;
    /** KTR parameter defaults (from {@code <parameters>} section). Used for {@code ${VAR}} substitution. */
    public Map<String, String> parameters;
    public List<StepDefinition> steps;
    public List<HopDefinition> hops;
}
