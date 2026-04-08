package com.pentaho.migration.api.domain;

import java.time.Instant;
import java.util.UUID;

public class YamlDefinition {

    private UUID id;
    private UUID projectId;
    private String filename;
    private DefinitionType definitionType;
    private String content;
    private Instant createdAt;

    public UUID getId()                            { return id; }
    public void setId(UUID id)                     { this.id = id; }
    public UUID getProjectId()                     { return projectId; }
    public void setProjectId(UUID p)               { this.projectId = p; }
    public String getFilename()                    { return filename; }
    public void setFilename(String f)              { this.filename = f; }
    public DefinitionType getDefinitionType()      { return definitionType; }
    public void setDefinitionType(DefinitionType t){ this.definitionType = t; }
    public String getContent()                     { return content; }
    public void setContent(String c)               { this.content = c; }
    public Instant getCreatedAt()                  { return createdAt; }
    public void setCreatedAt(Instant t)            { this.createdAt = t; }

    public enum DefinitionType { JOB, TRANSFORMATION }
}
