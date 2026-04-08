package com.pentaho.migration.model;

import java.util.List;

public class JobDefinition {
    public String name;
    public List<EntryDefinition> entries;
    public List<EntryHopDefinition> hops;
}
