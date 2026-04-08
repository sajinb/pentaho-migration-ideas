package com.pentaho.migration.api.controller;

import com.pentaho.migration.api.model.ExecutionRecord;
import com.pentaho.migration.api.service.ExecutionService;
import com.pentaho.migration.model.TransformationDefinition;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoints for submitting and monitoring KTR transformation executions.
 *
 * <h3>Typical flow</h3>
 * <pre>
 * POST /api/transformations/run        → 202 Accepted  { "id": "uuid", "status": "PENDING" }
 * GET  /api/executions/{id}            → 200 OK        { "id": "...", "status": "RUNNING" }
 * GET  /api/executions/{id}            → 200 OK        { "id": "...", "status": "COMPLETED", "durationMs": 123 }
 * </pre>
 */
@RestController
@RequestMapping("/api/transformations")
public class TransformationController {

    private final ExecutionService executionService;

    public TransformationController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Submit a transformation for asynchronous execution.
     *
     * <p>Request body: a {@link TransformationDefinition} serialised as JSON.
     * Example:
     * <pre>{@code
     * {
     *   "name": "my_pipeline",
     *   "steps": [
     *     { "id": "src", "type": "CsvInput",     "params": { "filePath": "/data/in.csv" } },
     *     { "id": "out", "type": "TextFileOutput","params": { "filePath": "/data/out.csv" } }
     *   ],
     *   "hops": [{ "from": "src", "to": "out", "enabled": true }]
     * }
     * }</pre>
     *
     * @return 202 Accepted with the execution record (poll {@code /api/executions/{id}} for status)
     */
    @PostMapping("/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ExecutionRecord run(@RequestBody TransformationDefinition def) {
        return executionService.submitTransformation(def);
    }

    /**
     * Convenience endpoint: get the status of a transformation execution.
     * (Same data as {@code GET /api/executions/{id}} — kept here for discoverability.)
     */
    @GetMapping("/{id}")
    public ResponseEntity<ExecutionRecord> status(@PathVariable String id) {
        return executionService.find(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
