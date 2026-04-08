package com.pentaho.migration.api.controller;

import com.pentaho.migration.api.model.ExecutionRecord;
import com.pentaho.migration.api.service.ExecutionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Unified endpoint for querying execution status regardless of whether the
 * execution was a transformation or a job.
 */
@RestController
@RequestMapping("/api/executions")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Get the status of any execution by ID.
     *
     * @return 200 with the execution record, or 404 if not found
     */
    @GetMapping("/{id}")
    public ResponseEntity<ExecutionRecord> get(@PathVariable String id) {
        return executionService.find(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all executions in the in-memory store (no pagination).
     */
    @GetMapping
    public List<ExecutionRecord> list() {
        return StreamSupport.stream(executionService.findAll().spliterator(), false)
                .toList();
    }
}
