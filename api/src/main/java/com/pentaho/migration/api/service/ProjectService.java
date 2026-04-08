package com.pentaho.migration.api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.pentaho.migration.api.NotFoundException;
import com.pentaho.migration.api.domain.*;
import com.pentaho.migration.api.repository.JobExecutionDao;
import com.pentaho.migration.api.repository.ProjectDao;
import com.pentaho.migration.converter.PentahoProjectConverter;
import com.pentaho.migration.engine.JobExecutor;
import com.pentaho.migration.model.JobDefinition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectService {

    private static final Logger LOG = Logger.getLogger(ProjectService.class.getName());

    private final ProjectDao        projectDao;
    private final JobExecutionDao   executionDao;
    private final PentahoProjectConverter converter;
    private final JobExecutor       jobExecutor;
    private final ExecutorService   executionPool;
    private final ObjectMapper      yamlMapper;

    public ProjectService(
            ProjectDao projectDao,
            JobExecutionDao executionDao,
            PentahoProjectConverter converter,
            JobExecutor jobExecutor,
            @Qualifier("executionPool") ExecutorService executionPool) {
        this.projectDao    = projectDao;
        this.executionDao  = executionDao;
        this.converter     = converter;
        this.jobExecutor   = jobExecutor;
        this.executionPool = executionPool;
        this.yamlMapper    = new ObjectMapper(new YAMLFactory());
    }

    // -------------------------------------------------------------------------
    // Create project
    // -------------------------------------------------------------------------

    public Project createProject(String name, MultipartFile kjbFile, List<MultipartFile> ktrFiles)
            throws Exception {

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
        projectDao.insert(project);

        UUID pid = project.getId();

        ProjectFile kjb = new ProjectFile();
        kjb.setProjectId(pid);
        kjb.setFilename(kjbName);
        kjb.setFileType(FileType.KJB);
        kjb.setContent(kjbFile.getBytes());
        projectDao.insertFile(kjb);
        project.getFiles().add(kjb);

        for (MultipartFile ktr : ktrFiles) {
            ProjectFile ktrFile = new ProjectFile();
            ktrFile.setProjectId(pid);
            ktrFile.setFilename(ktr.getOriginalFilename());
            ktrFile.setFileType(FileType.KTR);
            ktrFile.setContent(ktr.getBytes());
            projectDao.insertFile(ktrFile);
            project.getFiles().add(ktrFile);
        }

        return project;
    }

    // -------------------------------------------------------------------------
    // List / get
    // -------------------------------------------------------------------------

    public List<Project> listProjects() throws Exception {
        return projectDao.findAll();
    }

    public Project getProject(UUID id) throws Exception {
        return projectDao.findById(id)
                .orElseThrow(() -> new NotFoundException("Project not found: " + id));
    }

    // -------------------------------------------------------------------------
    // Convert: KJB/KTR → YAML
    // -------------------------------------------------------------------------

    public Project convert(UUID projectId) throws Exception {
        Project project = projectDao.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

        project.setStatus(ProjectStatus.CONVERTING);
        project.setErrorMessage(null);
        projectDao.update(project);
        projectDao.deleteYamls(projectId);

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

            List<YamlDefinition> yamls = new ArrayList<>();
            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(outputZip))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zin.closeEntry(); continue; }
                    String yamlName = entry.getName();
                    String content  = new String(zin.readAllBytes(), StandardCharsets.UTF_8);

                    YamlDefinition yaml = new YamlDefinition();
                    yaml.setProjectId(projectId);
                    yaml.setFilename(yamlName);
                    yaml.setDefinitionType(yamlName.equals(kjbYamlName)
                            ? YamlDefinition.DefinitionType.JOB
                            : YamlDefinition.DefinitionType.TRANSFORMATION);
                    yaml.setContent(content);
                    projectDao.insertYaml(yaml);
                    yamls.add(yaml);
                    zin.closeEntry();
                }
            }

            project.setStatus(ProjectStatus.CONVERTED);
            project.setYamlDefinitions(yamls);

        } catch (Exception e) {
            project.setStatus(ProjectStatus.CONVERSION_FAILED);
            project.setErrorMessage(e.getMessage());
        } finally {
            safeDelete(inputZip);
            safeDelete(outputZip);
        }

        projectDao.update(project);

        return projectDao.findById(projectId).orElseThrow();
    }

    // -------------------------------------------------------------------------
    // Execute job asynchronously
    // -------------------------------------------------------------------------

    public JobExecution execute(UUID projectId) throws Exception {
        Project project = projectDao.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));

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
        execution.setProjectId(projectId);
        execution.setStatus(JobExecution.ExecutionStatus.PENDING);
        executionDao.insert(execution);

        final UUID execId = execution.getId();
        executionPool.submit(() -> runJobAsync(execId, jobDef));

        return execution;
    }

    private void runJobAsync(UUID execId, JobDefinition jobDef) {
        Instant start = Instant.now();
        try {
            JobExecution exec = executionDao.findById(execId).orElseThrow();
            exec.setStatus(JobExecution.ExecutionStatus.RUNNING);
            exec.setStartedAt(start);
            executionDao.update(exec);

            boolean success;
            String errorMsg = null;
            try {
                success = jobExecutor.execute(jobDef);
                if (!success) errorMsg = "Job returned failure";
            } catch (Exception e) {
                success = false;
                errorMsg = e.getMessage();
            }

            exec = executionDao.findById(execId).orElseThrow();
            exec.setStatus(success
                    ? JobExecution.ExecutionStatus.COMPLETED
                    : JobExecution.ExecutionStatus.FAILED);
            exec.setErrorMessage(errorMsg);
            exec.setCompletedAt(Instant.now());
            exec.setDurationMs(Instant.now().toEpochMilli() - start.toEpochMilli());
            executionDao.update(exec);

        } catch (Exception e) {
            LOG.warning("Error in async execution " + execId + ": " + e.getMessage());
            try {
                Optional<JobExecution> opt = executionDao.findById(execId);
                if (opt.isPresent()) {
                    JobExecution ex = opt.get();
                    ex.setStatus(JobExecution.ExecutionStatus.FAILED);
                    ex.setErrorMessage("Internal error: " + e.getMessage());
                    ex.setCompletedAt(Instant.now());
                    ex.setDurationMs(Instant.now().toEpochMilli() - start.toEpochMilli());
                    executionDao.update(ex);
                }
            } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------------------
    // Execution queries
    // -------------------------------------------------------------------------

    public List<JobExecution> listExecutions(UUID projectId) throws Exception {
        return executionDao.findByProjectId(projectId);
    }

    public JobExecution getExecution(UUID projectId, UUID execId) throws Exception {
        return executionDao.findByIdAndProjectId(execId, projectId)
                .orElseThrow(() -> new NotFoundException(
                        "Execution not found: " + execId + " for project " + projectId));
    }

    // -------------------------------------------------------------------------

    private static void safeDelete(Path p) {
        if (p != null) try { Files.deleteIfExists(p); } catch (IOException ignored) {}
    }
}
