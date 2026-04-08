package com.pentaho.migration.api.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Tracks the lifecycle of a single asynchronous pipeline or job execution.
 *
 * <p>Thread-safe via {@code synchronized} on state transitions.
 *
 * <p>{@link JsonAutoDetect} enables Jackson to (de)serialize private fields directly,
 * which avoids boilerplate {@code @JsonProperty} annotations on every getter.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public class ExecutionRecord {

    public enum Status { PENDING, RUNNING, COMPLETED, FAILED }

    private String  id;
    private String  type;          // "transformation" or "job"
    private volatile Status  status;
    private Instant startedAt;
    private Instant completedAt;
    private Long    durationMs;
    private String  errorMessage;

    /** No-arg constructor for Jackson deserialization. */
    ExecutionRecord() {}

    private ExecutionRecord(String id, String type) {
        this.id     = id;
        this.type   = type;
        this.status = Status.PENDING;
    }

    public static ExecutionRecord pending(String id, String type) {
        return new ExecutionRecord(id, type);
    }

    public synchronized void start() {
        status    = Status.RUNNING;
        startedAt = Instant.now();
    }

    public synchronized void complete() {
        status      = Status.COMPLETED;
        completedAt = Instant.now();
        durationMs  = completedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    public synchronized void fail(String message) {
        status       = Status.FAILED;
        completedAt  = Instant.now();
        durationMs   = completedAt.toEpochMilli() - startedAt.toEpochMilli();
        errorMessage = message;
    }

    // --- Getters ---

    public String  getId()           { return id; }
    public String  getType()         { return type; }
    public Status  getStatus()       { return status; }
    public Instant getStartedAt()    { return startedAt; }
    public Instant getCompletedAt()  { return completedAt; }
    public Long    getDurationMs()   { return durationMs; }
    public String  getErrorMessage() { return errorMessage; }
}
