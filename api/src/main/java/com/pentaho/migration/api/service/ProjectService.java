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
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

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

        project = projectRepository.save(project);
        initCollections(project);
        return project;
    }

    // -------------------------------------------------------------------------
    // List / get
    // -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Project> listProjects() {
        List<Project> projects = projectRepository.findAllOrderByCreatedAtDesc();
        projects.forEach(ProjectService::initCollections);
        return projects;
    }

    @Transactional(readOnly = true)
    public Project getProject(UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + id));
        initCollections(project);
        return project;
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
                Set<String> usedEntryNames = new HashSet<>();
                for (ProjectFile f : project.getFiles()) {
                    String entryName = uniqueZipName(f.getFilename(), usedEntryNames);
                    zout.putNextEntry(new ZipEntry(entryName));
                    zout.write(f.getContent());
                    zout.closeEntry();
                }
            }

            converter.convert(inputZip, outputZip);

            try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(outputZip))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    if (entry.isDirectory()) { zin.closeEntry(); continue; }
                    String yamlName = entry.getName();
                    String content  = new String(zin.readAllBytes(), StandardCharsets.UTF_8);

                    // Detect type by content: job YAML has "entries:" but no "steps:"
                    YamlDefinition.DefinitionType defType =
                            (content.contains("entries:") && !content.contains("steps:"))
                            ? YamlDefinition.DefinitionType.JOB
                            : YamlDefinition.DefinitionType.TRANSFORMATION;

                    YamlDefinition yaml = new YamlDefinition();
                    yaml.setProject(project);
                    yaml.setFilename(yamlName);
                    yaml.setDefinitionType(defType);
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

        project = projectRepository.save(project);
        initCollections(project);
        return project;
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

        // Snapshot all YAML content (filename → content) so the async thread has
        // no JPA session dependency and can write them to a temp directory.
        Map<String, String> yamlContents = new HashMap<>();
        project.getYamlDefinitions().forEach(y -> yamlContents.put(y.getFilename(), y.getContent()));

        JobExecution execution = new JobExecution();
        execution.setProject(project);
        execution.setStatus(ExecutionStatus.PENDING);
        execution = jobExecutionRepository.save(execution);

        final UUID execId = execution.getId();

        // Submit AFTER the transaction commits so the PENDING row is visible to the
        // async thread when it calls findById(execId). Without this the thread races
        // the commit, findById returns empty, orElseThrow() throws silently, and the
        // execution is stuck in PENDING forever.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executionPool.submit(() -> runJobAsync(execId, jobDef, yamlContents));
            }
        });

        return execution;
    }

    private void runJobAsync(UUID execId, JobDefinition jobDef, Map<String, String> yamlContents) {
        log.info("[exec:{}] Starting job '{}'", execId, jobDef.name);
        JobExecution exec = jobExecutionRepository.findById(execId).orElseThrow();
        exec.setStatus(ExecutionStatus.RUNNING);
        exec.setStartedAt(Instant.now());
        jobExecutionRepository.save(exec);

        Path yamlDir = null;
        Instant start = Instant.now();
        try {
            // Write every YAML definition to a temp directory so RunTransformationEntry
            // can read them by filename (transformationPath is the bare filename).
            yamlDir = Files.createTempDirectory("pentaho-exec-");
            log.info("[exec:{}] Writing {} YAML file(s) to {}", execId, yamlContents.size(), yamlDir);
            for (Map.Entry<String, String> e : yamlContents.entrySet()) {
                Files.writeString(yamlDir.resolve(e.getKey()), e.getValue());
            }

            Map<String, String> context = new HashMap<>();
            context.put("basePath", yamlDir.toString());

            boolean success = jobExecutor.execute(jobDef, context);
            exec = jobExecutionRepository.findById(execId).orElseThrow();
            exec.setStatus(success ? ExecutionStatus.COMPLETED : ExecutionStatus.FAILED);
            if (!success) {
                exec.setErrorMessage("Job returned failure");
                log.warn("[exec:{}] Job '{}' completed with failure result", execId, jobDef.name);
            } else {
                log.info("[exec:{}] Job '{}' completed successfully", execId, jobDef.name);
            }
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("[exec:{}] Job '{}' threw exception: {}", execId, jobDef.name, msg, e);
            exec = jobExecutionRepository.findById(execId).orElseThrow();
            exec.setStatus(ExecutionStatus.FAILED);
            exec.setErrorMessage(msg);
        } finally {
            deleteTempDir(yamlDir);
        }
        exec.setCompletedAt(Instant.now());
        exec.setDurationMs(Instant.now().toEpochMilli() - start.toEpochMilli());
        jobExecutionRepository.save(exec);
    }

    private static void deleteTempDir(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                  .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
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

    /** Returns {@code name} on first use; appends _2, _3 … when the name is already taken. */
    private static String uniqueZipName(String name, Set<String> used) {
        if (used.add(name)) return name;
        int dot  = name.lastIndexOf('.');
        String base = dot < 0 ? name : name.substring(0, dot);
        String ext  = dot < 0 ? ""   : name.substring(dot);
        int counter = 2;
        String candidate;
        do { candidate = base + "_" + counter++ + ext; } while (!used.add(candidate));
        return candidate;
    }

    /** Force-initialize all lazy collections so the entity can be used outside this session. */
    private static void initCollections(Project project) {
        Hibernate.initialize(project.getFiles());
        Hibernate.initialize(project.getYamlDefinitions());
        Hibernate.initialize(project.getExecutions());
    }
}
