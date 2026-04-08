package com.pentaho.migration.api.controller;

import com.pentaho.migration.api.model.ExecutionRecord;
import com.pentaho.migration.api.service.ExecutionService;
import com.pentaho.migration.model.JobDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for submitting and monitoring KJB job executions.
 *
 * <h3>Typical flow</h3>
 * <pre>
 * POST /api/jobs/run   → 202 Accepted  { "id": "uuid", "status": "PENDING" }
 * GET  /api/executions/{id}            → poll for RUNNING → COMPLETED / FAILED
 * </pre>
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final ExecutionService executionService;

    public JobController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Submit a job for asynchronous execution.
     *
     * <p>Request body: a {@link JobDefinition} serialised as JSON. Example:
     * <pre>{@code
     * {
     *   "name": "daily_pipeline",
     *   "entries": [
     *     { "id": "start",   "type": "Start",             "params": {} },
     *     { "id": "run_ktr", "type": "RunTransformation", "params": { "transformationPath": "/jobs/sort.yaml" } },
     *     { "id": "end",     "type": "Success",           "params": {} }
     *   ],
     *   "hops": [
     *     { "from": "start",   "to": "run_ktr", "evaluation": "unconditional" },
     *     { "from": "run_ktr", "to": "end",     "evaluation": "success" }
     *   ]
     * }
     * }</pre>
     *
     * @return 202 Accepted with the execution record
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionRecord run(@RequestBody JobDefinition def) {
        return executionService.submitJob(def);
    }

    /**
     * Convenience endpoint: get the status of a job execution.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ExecutionRecord> status(@PathVariable String id) {
        return executionService.find(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
