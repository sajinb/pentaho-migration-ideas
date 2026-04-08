package com.pentaho.migration.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "yaml_definitions")
public class YamlDefinition {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String filename;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, name = "definition_type")
    private DefinitionType definitionType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId()                { return id; }
    public Project getProject()        { return project; }
    public void setProject(Project p)  { this.project = p; }
    public String getFilename()        { return filename; }
    public void setFilename(String f)  { this.filename = f; }
    public DefinitionType getDefinitionType()       { return definitionType; }
    public void setDefinitionType(DefinitionType t) { this.definitionType = t; }
    public String getContent()         { return content; }
    public void setContent(String c)   { this.content = c; }
    public Instant getCreatedAt()      { return createdAt; }

    public enum DefinitionType { JOB, TRANSFORMATION }
}
