package com.pentaho.migration.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_files")
public class ProjectFile {

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
    @Column(nullable = false, length = 10)
    private FileType fileType;

    @Lob
    @Column(nullable = false)
    private byte[] content;

    @Column(nullable = false)
    private long sizeBytes;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId()           { return id; }
    public Project getProject()   { return project; }
    public void setProject(Project p) { this.project = p; }
    public String getFilename()   { return filename; }
    public void setFilename(String f) { this.filename = f; }
    public FileType getFileType() { return fileType; }
    public void setFileType(FileType t) { this.fileType = t; }
    public byte[] getContent()    { return content; }
    public void setContent(byte[] c) { this.content = c; sizeBytes = c.length; }
    public long getSizeBytes()    { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
}
