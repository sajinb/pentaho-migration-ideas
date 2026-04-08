package com.pentaho.migration.model;

import java.util.List;

public class TransformationDefinition {
    public String name;
    public List<StepDefinition> steps;
    public List<HopDefinition> hops;
}
