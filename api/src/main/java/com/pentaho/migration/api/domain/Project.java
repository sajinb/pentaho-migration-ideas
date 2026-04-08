package com.pentaho.migration.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProjectStatus status = ProjectStatus.UPLOADED;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<ProjectFile> files = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @OrderBy("createdAt ASC")
    private List<YamlDefinition> yamlDefinitions = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true,
               fetch = FetchType.LAZY)
    @OrderBy("createdAt DESC")
    private List<JobExecution> executions = new ArrayList<>();

    public UUID getId()                   { return id; }
    public String getName()               { return name; }
    public void setName(String n)         { this.name = n; }
    public ProjectStatus getStatus()      { return status; }
    public void setStatus(ProjectStatus s){ this.status = s; }
    public String getErrorMessage()       { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m; }
    public Instant getCreatedAt()         { return createdAt; }
    public Instant getUpdatedAt()         { return updatedAt; }
    public List<ProjectFile> getFiles()   { return files; }
    public List<YamlDefinition> getYamlDefinitions() { return yamlDefinitions; }
    public List<JobExecution> getExecutions()         { return executions; }
}
