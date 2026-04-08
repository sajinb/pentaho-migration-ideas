package com.pentaho.migration.api.controller;

import com.pentaho.migration.api.NotFoundException;
import com.pentaho.migration.api.domain.JobExecution;
import com.pentaho.migration.api.domain.Project;
import com.pentaho.migration.api.dto.JobExecutionDto;
import com.pentaho.migration.api.dto.ProjectDto;
import com.pentaho.migration.api.dto.ProjectSummaryDto;
import com.pentaho.migration.api.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * REST API for the project-based Pentaho migration workflow.
 *
 * <pre>
 * POST   /api/projects                        Upload KJB + KTR files → create project
 * GET    /api/projects                        List all projects (summary)
 * GET    /api/projects/{id}                   Get project detail
 * POST   /api/projects/{id}/convert           Generate YAML from uploaded files
 * POST   /api/projects/{id}/execute           Run the job (async, 202 Accepted)
 * GET    /api/projects/{id}/executions        List executions for a project
 * GET    /api/projects/{id}/executions/{eid}  Get a single execution
 * </pre>
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectDto> upload(
            @RequestParam("name") String name,
            @RequestParam("kjb")  MultipartFile kjb,
            @RequestParam("ktrs") List<MultipartFile> ktrs) throws Exception {

        Project project = projectService.createProject(name, kjb, ktrs);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ProjectDto.from(project));
    }

    // -------------------------------------------------------------------------
    // List / detail
    // -------------------------------------------------------------------------

    @GetMapping
    public List<ProjectSummaryDto> list() throws Exception {
        return projectService.listProjects().stream()
                .map(ProjectSummaryDto::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable UUID id) throws Exception {
        return ProjectDto.from(projectService.getProject(id));
    }

    // -------------------------------------------------------------------------
    // Convert
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/convert")
    public ProjectDto convert(@PathVariable UUID id) throws Exception {
        return ProjectDto.from(projectService.convert(id));
    }

    // -------------------------------------------------------------------------
    // Execute
    // -------------------------------------------------------------------------

    @PostMapping("/{id}/execute")
    public ResponseEntity<JobExecutionDto> execute(@PathVariable UUID id) throws Exception {
        JobExecution exec = projectService.execute(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(JobExecutionDto.from(exec));
    }

    // -------------------------------------------------------------------------
    // Execution queries
    // -------------------------------------------------------------------------

    @GetMapping("/{id}/executions")
    public List<JobExecutionDto> listExecutions(@PathVariable UUID id) throws Exception {
        return projectService.listExecutions(id).stream()
                .map(JobExecutionDto::from)
                .toList();
    }

    @GetMapping("/{id}/executions/{eid}")
    public JobExecutionDto getExecution(
            @PathVariable UUID id,
            @PathVariable UUID eid) throws Exception {
        return JobExecutionDto.from(projectService.getExecution(id, eid));
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> badRequest(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(ex.getMessage()));
    }

    public record ErrorResponse(String message) {}
}
