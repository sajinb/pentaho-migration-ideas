package com.pentaho.migration.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.api.domain.*;
import com.pentaho.migration.api.domain.JobExecution.ExecutionStatus;
import com.pentaho.migration.api.repository.JobExecutionRepository;
import com.pentaho.migration.api.repository.ProjectRepository;
import com.pentaho.migration.converter.PentahoProjectConverter;
import com.pentaho.migration.engine.JobExecutor;
import com.pentaho.migration.model.JobDefinition;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectService {

    private final ProjectRepository       projectRepository;
    private final JobExecutionRepository  jobExecutionRepository;
    private final PentahoProjectConverter converter;
    private final JobExecutor             jobExecutor;
    private final ExecutorService         executionPool;
    private final ObjectMapper            yamlMapper;

    public ProjectService(
            ProjectRepository projectRepository,
            JobExecutionRepository jobExecutionRepository,
            PentahoProjectConverter converter,
            JobExecutor jobExecutor,
            @Qualifier("executionPool") ExecutorService executionPool) {
        this.projectRepository      = projectRepository;
        this.jobExecutionRepository = jobExecutionRepository;
        this.converter              = converter;
        this.jobExecutor            = jobExecutor;
        this.executionPool          = executionPool;
        this.yamlMapper             = new ObjectMapper(new YAMLFactory());
    }

    // -------------------------------------------------------------------------
    // Create project from uploaded files
    // -------------------------------------------------------------------------

    @Transactional
    public Project createProject(String name, MultipartFile kjbFile, List<MultipartFile> ktrFiles)
            throws IOException {

        if (kjbFile == null || kjbFile.isEmpty())
            throw new IllegalArgumentException("A .kjb file is required");
        if (ktrFiles == null || ktrFiles.isEmpty())
            throw new IllegalArgumentException("At least one .ktr file is required");

        String kjbName = kjbFile.getOriginalFilename();
        if (kjbName == null || !kjbName.toLowerCase().endsWith(".kjb"))
            throw new IllegalArgumentException("The KJB file must have a .kjb extension");
        for (MultipartFile ktr : ktrFiles) {
            String n = ktr.getOriginalFilename();
            if (n == null || !n.toLowerCase().endsWith(".ktr"))
                throw new IllegalArgumentException("All KTR files must have a .ktr extension; got: " + n);
        }

        Project project = new Project();
        project.setName(name);
        project.setStatus(ProjectStatus.UPLOADED);
        project = projectRepository.save(project);

        ProjectFile kjb = new ProjectFile();
        kjb.setProject(project);
        kjb.setFilename(kjbName);
        kjb.setFileType(FileType.KJB);
        kjb.setContent(kjbFile.getBytes());
        project.getFiles().add(kjb);

        for (MultipartFile ktr : ktrFiles) {
            ProjectFile ktrFile = new ProjectFile();
            ktrFile.setProject(project);
            ktrFile.setFilename(ktr.getOriginalFilename());
            ktrFile.setFileType(FileType.KTR);
            ktrFile.setContent(ktr.getBytes());
            project.getFiles().add(ktrFile);
        }

        return projectRepository.save(project);
    }

    // -------------------------------------------------------------------------
    // List / get
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        return projectRepository.findAllOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Project getProject(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + id));
    }

    // -------------------------------------------------------------------------
    // Convert: KJB/KTR → YAML
    // -------------------------------------------------------------------------

    @Transactional
    public Project convert(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));

        project.getYamlDefinitions().clear();
        project.setStatus(ProjectStatus.CONVERTING);
        project.setErrorMessage(null);
        project = projectRepository.save(project);

        Path inputZip  = null;
        Path outputZip = null;
        try {
            inputZip  = Files.createTempFile("pentaho-in-",  ".zip");
            outputZip = Files.createTempFile("pentaho-out-", ".zip");

            try (ZipOutputStream zout = new ZipOutputStream(Files.newOutputStream(inputZip))) {
                for (ProjectFile f : project.getFiles()) {
                    zout.putNextEntry(new ZipEntry(f.getFilename()));
                    zout.write(f.getContent());
                    zout.closeEntry();
                }
            }

            converter.convert(inputZip, outputZip);

            String kjbYamlName = project.getFiles().stream()
                    .filter(f -> f.getFileType() == FileType.KJB)
                    .map(ProjectFile::getFilename)
                    .findFirst().orElse("")
                    .replaceAll("(?i)\\.kjb$", ".yaml");

            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(outputZip))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zin.closeEntry(); continue; }
                    String yamlName = entry.getName();
                    String content  = new String(zin.readAllBytes(), StandardCharsets.UTF_8);

                    YamlDefinition yaml = new YamlDefinition();
                    yaml.setProject(project);
                    yaml.setFilename(yamlName);
                    yaml.setDefinitionType(yamlName.equals(kjbYamlName)
                            ? YamlDefinition.DefinitionType.JOB
                            : YamlDefinition.DefinitionType.TRANSFORMATION);
                    yaml.setContent(content);
                    project.getYamlDefinitions().add(yaml);
                    zin.closeEntry();
                }
            }

            project.setStatus(ProjectStatus.CONVERTED);

        } catch (Exception e) {
            project.setStatus(ProjectStatus.CONVERSION_FAILED);
            project.setErrorMessage(e.getMessage());
        } finally {
            safeDelete(inputZip);
            safeDelete(outputZip);
        }

        return projectRepository.save(project);
    }

    // -------------------------------------------------------------------------
    // Execute job asynchronously
    // -------------------------------------------------------------------------

    @Transactional
    public JobExecution execute(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));

        if (project.getStatus() != ProjectStatus.CONVERTED)
            throw new IllegalStateException(
                    "Project must be CONVERTED before execution. Current: " + project.getStatus());

        String jobYamlContent = project.getYamlDefinitions().stream()
                .filter(y -> y.getDefinitionType() == YamlDefinition.DefinitionType.JOB)
                .map(YamlDefinition::getContent)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No JOB YAML found for project"));

        JobDefinition jobDef;
        try {
            jobDef = yamlMapper.readValue(jobYamlContent, JobDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse job YAML: " + e.getMessage(), e);
        }

        JobExecution execution = new JobExecution();
        execution.setProject(project);
        execution.setStatus(ExecutionStatus.PENDING);
        execution = jobExecutionRepository.save(execution);

        final UUID execId = execution.getId();
        executionPool.submit(() -> runJobAsync(execId, jobDef));

        return execution;
    }

    private void runJobAsync(UUID execId, JobDefinition jobDef) {
        JobExecution exec = jobExecutionRepository.findById(execId).orElseThrow();
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setStartedAt(Instant.now());
        jobExecutionRepository.save(exec);

        Instant start = Instant.now();
        try {
            boolean success = jobExecutor.execute(jobDef);
            exec = jobExecutionRepository.findById(execId).orElseThrow();
            exec.setStatus(success ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED);
            if (!success) exec.setErrorMessage("Job returned failure");
        } catch (Exception e) {
            exec = jobExecutionRepository.findById(execId).orElseThrow();
            exec.setStatus(ExecutionStatus.FAILED);
            exec.setErrorMessage(e.getMessage());
        }
        exec.setCompletedAt(Instant.now());
        exec.setDurationMs(Instant.now().toEpochMilli() - start.toEpochMilli());
        jobExecutionRepository.save(exec);
    }

    // -------------------------------------------------------------------------
    // Execution queries
    // -------------------------------------------------------------------------

    public List<JobExecution> listExecutions(UUID projectId) {
        return jobExecutionRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }

    public JobExecution getExecution(UUID projectId, UUID execId) {
        return jobExecutionRepository.findByIdAndProjectId(execId, projectId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Execution not found: " + execId + " for project " + projectId));
    }

    // -------------------------------------------------------------------------

    private static void safeDelete(Path p) {
        if (p != null) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
