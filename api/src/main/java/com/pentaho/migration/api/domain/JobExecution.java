package com.pentaho.migration.api.domain;

import java.time.Instant;
import java.util.UUID;

public class JobExecution {

    private UUID id;
    private UUID projectId;
    private ExecutionStatus status = ExecutionStatus.PENDING;
    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;
    private String errorMessage;
    private Instant createdAt;

    public UUID getId()                        { return id; }
    public void setId(UUID id)                 { this.id = id; }
    public UUID getProjectId()                 { return projectId; }
    public void setProjectId(UUID p)           { this.projectId = p; }
    public ExecutionStatus getStatus()         { return status; }
    public void setStatus(ExecutionStatus s)   { this.status = s; }
    public Instant getStartedAt()              { return startedAt; }
    public void setStartedAt(Instant t)        { this.startedAt = t; }
    public Instant getCompletedAt()            { return completedAt; }
    public void setCompletedAt(Instant t)      { this.completedAt = t; }
    public Long getDurationMs()                { return durationMs; }
    public void setDurationMs(Long d)          { this.durationMs = d; }
    public String getErrorMessage()            { return errorMessage; }
    public void setErrorMessage(String m)      { this.errorMessage = m; }
    public Instant getCreatedAt()              { return createdAt; }
    public void setCreatedAt(Instant t)        { this.createdAt = t; }

    public enum ExecutionStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
