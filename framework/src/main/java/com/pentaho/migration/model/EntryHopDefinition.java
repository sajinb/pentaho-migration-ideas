package com.pentaho.migration.model;

public class EntryHopDefinition {
    public String from;
    public String to;
    /** "success" | "failure" | "unconditional" */
    public String evaluation;
}
