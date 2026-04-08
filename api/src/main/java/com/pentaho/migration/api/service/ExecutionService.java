package com.pentaho.migration.api.service;

import com.pentaho.migration.api.model.ExecutionRecord;
import com.pentaho.migration.engine.JobExecutor;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.model.JobDefinition;
import com.pentaho.migration.model.TransformationDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Submits transformation and job executions to a background thread pool and
 * tracks their status in an in-memory store.
 *
 * <p>The in-memory store is suitable for development and single-instance deployments.
 * For multi-instance or persistent status, replace the {@link ConcurrentHashMap} with
 * a shared store (e.g. Redis, JDBC).
 */
@Service
public class ExecutionService {

    private final TransformationExecutor transformationExecutor;
    private final JobExecutor            jobExecutor;
    private final ExecutorService        pool;

    private final Map<String, ExecutionRecord> store = new ConcurrentHashMap<>();

    public ExecutionService(
            TransformationExecutor transformationExecutor,
            JobExecutor jobExecutor,
            @Qualifier("executionPool") ExecutorService pool) {
        this.transformationExecutor = transformationExecutor;
        this.jobExecutor            = jobExecutor;
        this.pool                   = pool;
    }

    /**
     * Submits a transformation for asynchronous execution.
     *
     * @return an {@link ExecutionRecord} with status {@code PENDING}; caller polls
     *         {@link #find(String)} to track progress.
     */
    public ExecutionRecord submitTransformation(TransformationDefinition def) {
        ExecutionRecord rec = ExecutionRecord.pending(UUID.randomUUID().toString(), "transformation");
        store.put(rec.getId(), rec);
        pool.submit(() -> {
            rec.start();
            try {
                transformationExecutor.execute(def);
                rec.complete();
            } catch (Exception e) {
                rec.fail(rootMessage(e));
            }
        });
        return rec;
    }

    /**
     * Submits a job for asynchronous execution.
     *
     * @return an {@link ExecutionRecord} with status {@code PENDING}.
     */
    public ExecutionRecord submitJob(JobDefinition def) {
        ExecutionRecord rec = ExecutionRecord.pending(UUID.randomUUID().toString(), "job");
        store.put(rec.getId(), rec);
        pool.submit(() -> {
            rec.start();
            try {
                boolean ok = jobExecutor.execute(def);
                if (ok) {
                    rec.complete();
                } else {
                    rec.fail("Job returned failure (see job entry logs for details)");
                }
            } catch (Exception e) {
                rec.fail(rootMessage(e));
            }
        });
        return rec;
    }

    /**
     * Returns the execution record for the given ID, if it exists.
     */
    public Optional<ExecutionRecord> find(String id) {
        return Optional.ofNullable(store.get(id));
    }

    /**
     * Returns all recorded executions (most recent first is not guaranteed —
     * iteration order of {@link ConcurrentHashMap} is unspecified).
     */
    public Iterable<ExecutionRecord> findAll() {
        return store.values();
    }

    // -------------------------------------------------------------------------

    private static String rootMessage(Throwable t) {
        while (t.getCause() != null) t = t.getCause();
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
