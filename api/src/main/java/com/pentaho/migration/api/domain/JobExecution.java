package com.pentaho.migration.api.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_executions")
public class JobExecution {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status = ExecutionStatus.PENDING;

    private Instant startedAt;
    private Instant completedAt;
    private Long durationMs;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public UUID getId()                   { return id; }
    public Project getProject()           { return project; }
    public void setProject(Project p)     { this.project = p; }
    public ExecutionStatus getStatus()    { return status; }
    public void setStatus(ExecutionStatus s) { this.status = s; }
    public Instant getStartedAt()         { return startedAt; }
    public void setStartedAt(Instant t)   { this.startedAt = t; }
    public Instant getCompletedAt()       { return completedAt; }
    public void setCompletedAt(Instant t) { this.completedAt = t; }
    public Long getDurationMs()           { return durationMs; }
    public void setDurationMs(Long d)     { this.durationMs = d; }
    public String getErrorMessage()       { return errorMessage; }
    public void setErrorMessage(String m) { this.errorMessage = m; }
    public Instant getCreatedAt()         { return createdAt; }

    public enum ExecutionStatus { PENDING, RUNNING, COMPLETED, FAILED }
}
