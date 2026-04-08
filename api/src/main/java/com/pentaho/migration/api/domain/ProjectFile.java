package com.pentaho.migration.api.domain;

import java.time.Instant;
import java.util.UUID;

public class ProjectFile {

    private UUID id;
    private UUID projectId;
    private String filename;
    private FileType fileType;
    private byte[] content;
    private long sizeBytes;
    private Instant createdAt;

    public UUID getId()                    { return id; }
    public void setId(UUID id)             { this.id = id; }
    public UUID getProjectId()             { return projectId; }
    public void setProjectId(UUID p)       { this.projectId = p; }
    public String getFilename()            { return filename; }
    public void setFilename(String f)      { this.filename = f; }
    public FileType getFileType()          { return fileType; }
    public void setFileType(FileType t)    { this.fileType = t; }
    public byte[] getContent()             { return content; }
    public void setContent(byte[] c)       { this.content = c; if (c != null) sizeBytes = c.length; }
    public long getSizeBytes()             { return sizeBytes; }
    public void setSizeBytes(long s)       { this.sizeBytes = s; }
    public Instant getCreatedAt()          { return createdAt; }
    public void setCreatedAt(Instant t)    { this.createdAt = t; }
}
