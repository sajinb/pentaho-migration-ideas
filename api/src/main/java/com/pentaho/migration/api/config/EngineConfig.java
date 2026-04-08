package com.pentaho.migration.api.config;

import com.pentaho.migration.converter.PentahoProjectConverter;
import com.pentaho.migration.engine.JobExecutor;
import com.pentaho.migration.engine.TransformationExecutor;
import com.pentaho.migration.entry.JobEntryRegistry;
import com.pentaho.migration.step.StepRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the core migration engine components as Spring beans.
 *
 * <p>All engine objects are stateless (or thread-safe) and safe for singleton scope:
 * <ul>
 *   <li>{@link StepRegistry} / {@link JobEntryRegistry} — read-only after construction
 *   <li>{@link TransformationExecutor} / {@link JobExecutor} — no mutable state; safe to share
 * </ul>
 */
@Configuration
public class EngineConfig {

    @Value("${engine.temp-dir:#{systemProperties['java.io.tmpdir']}}")
    private String tempDir;

    @Value("${engine.execution-pool-size:4}")
    private int poolSize;

    @Bean
    public StepRegistry stepRegistry() {
        return StepRegistry.withDefaults();
    }

    @Bean
    public JobEntryRegistry jobEntryRegistry() {
        return JobEntryRegistry.withDefaults();
    }

    @Bean
    public TransformationExecutor transformationExecutor(StepRegistry stepRegistry) {
        return new TransformationExecutor(stepRegistry)
                .withTempDir(Path.of(tempDir));
    }

    @Bean
    public JobExecutor jobExecutor(JobEntryRegistry jobEntryRegistry) {
        return new JobExecutor(jobEntryRegistry);
    }

    @Bean
    public PentahoProjectConverter pentahoProjectConverter() {
        return new PentahoProjectConverter();
    }

    /**
     * Thread pool for async pipeline execution.
     * Uses virtual threads (Java 21) for lightweight concurrency.
     */
    @Bean(name = "executionPool", destroyMethod = "shutdown")
    public ExecutorService executionPool() {
        return Executors.newFixedThreadPool(poolSize);
    }
}
