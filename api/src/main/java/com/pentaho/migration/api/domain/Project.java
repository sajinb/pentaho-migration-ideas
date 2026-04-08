package com.pentaho.migration.api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class Project {

    private UUID id;
    private String name;
    private ProjectStatus status = ProjectStatus.UPLOADED;
    private String errorMessage;
    private Instant createdAt;
    private Instant updatedAt;
    private List<ProjectFile> files = new ArrayList<>();
    private List<YamlDefinition> yamlDefinitions = new ArrayList<>();
    private List<JobExecution> executions = new ArrayList<>();

    public UUID getId()                              { return id; }
    public void setId(UUID id)                       { this.id = id; }
    public String getName()                          { return name; }
    public void setName(String n)                    { this.name = n; }
    public ProjectStatus getStatus()                 { return status; }
    public void setStatus(ProjectStatus s)           { this.status = s; }
    public String getErrorMessage()                  { return errorMessage; }
    public void setErrorMessage(String m)            { this.errorMessage = m; }
    public Instant getCreatedAt()                    { return createdAt; }
    public void setCreatedAt(Instant t)              { this.createdAt = t; }
    public Instant getUpdatedAt()                    { return updatedAt; }
    public void setUpdatedAt(Instant t)              { this.updatedAt = t; }
    public List<ProjectFile> getFiles()              { return files; }
    public void setFiles(List<ProjectFile> f)        { this.files = f; }
    public List<YamlDefinition> getYamlDefinitions() { return yamlDefinitions; }
    public void setYamlDefinitions(List<YamlDefinition> y) { this.yamlDefinitions = y; }
    public List<JobExecution> getExecutions()        { return executions; }
    public void setExecutions(List<JobExecution> e)  { this.executions = e; }
}
