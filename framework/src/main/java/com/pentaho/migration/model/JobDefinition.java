package com.pentaho.migration.model;

import java.util.List;
import java.util.Map;

public class JobDefinition {
    public String name;
    /** KJB-level parameters with their default values. Used as variable context for entries. */
    public Map<String, String> parameters;
    public List<EntryDefinition> entries;
    public List<EntryHopDefinition> hops;
}
