package com.pentaho.migration.api.repository;

import com.pentaho.migration.api.config.DatabaseConfig;
import com.pentaho.migration.api.domain.*;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class ProjectDao {

    private final DatabaseConfig db;

    public ProjectDao(DatabaseConfig db) { this.db = db; }

    // -------------------------------------------------------------------------
    // Project CRUD
    // -------------------------------------------------------------------------

    public Project insert(Project p) throws SQLException {
        if (p.getId() == null) p.setId(UUID.randomUUID());
        Instant now = Instant.now();
        p.setCreatedAt(now);
        p.setUpdatedAt(now);

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO projects (id, name, status, error_message, created_at, updated_at)" +
                " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, p.getId().toString());
            ps.setString(2, p.getName());
            ps.setString(3, p.getStatus().name());
            ps.setString(4, p.getErrorMessage());
            ps.setTimestamp(5, Timestamp.from(p.getCreatedAt()));
            ps.setTimestamp(6, Timestamp.from(p.getUpdatedAt()));
            ps.executeUpdate();
        }
        return p;
    }

    public void update(Project p) throws SQLException {
        p.setUpdatedAt(Instant.now());
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE projects SET status=?, error_message=?, updated_at=? WHERE id=?")) {
            ps.setString(1, p.getStatus().name());
            ps.setString(2, p.getErrorMessage());
            ps.setTimestamp(3, Timestamp.from(p.getUpdatedAt()));
            ps.setString(4, p.getId().toString());
            ps.executeUpdate();
        }
    }

    public Optional<Project> findById(UUID id) throws SQLException {
        Project p;
        try (Connection conn = db.getConnection()) {
            p = queryProject(conn, id);
            if (p == null) return Optional.empty();
            p.setFiles(loadFiles(conn, id, true));
            p.setYamlDefinitions(loadYamls(conn, id, true));
            p.setExecutions(loadExecutions(conn, id, false));
        }
        return Optional.of(p);
    }

    /** Returns all projects with lightweight associations (no binary content). */
    public List<Project> findAll() throws SQLException {
        List<Project> projects = new ArrayList<>();
        try (Connection conn = db.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT * FROM projects ORDER BY created_at DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) projects.add(mapProject(rs));
            }
            for (Project p : projects) {
                p.setFiles(loadFiles(conn, p.getId(), false));
                p.setYamlDefinitions(loadYamls(conn, p.getId(), false));
                p.setExecutions(loadLatestExecution(conn, p.getId()));
            }
        }
        return projects;
    }

    // -------------------------------------------------------------------------
    // ProjectFile
    // -------------------------------------------------------------------------

    public void insertFile(ProjectFile f) throws SQLException {
        if (f.getId() == null) f.setId(UUID.randomUUID());
        f.setCreatedAt(Instant.now());

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO project_files" +
                " (id, project_id, filename, file_type, content, size_bytes, created_at)" +
                " VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, f.getId().toString());
            ps.setString(2, f.getProjectId().toString());
            ps.setString(3, f.getFilename());
            ps.setString(4, f.getFileType().name());
            ps.setBytes(5, f.getContent());
            ps.setLong(6, f.getSizeBytes());
            ps.setTimestamp(7, Timestamp.from(f.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // YamlDefinition
    // -------------------------------------------------------------------------

    public void insertYaml(YamlDefinition y) throws SQLException {
        if (y.getId() == null) y.setId(UUID.randomUUID());
        y.setCreatedAt(Instant.now());

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO yaml_definitions" +
                " (id, project_id, filename, definition_type, content, created_at)" +
                " VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, y.getId().toString());
            ps.setString(2, y.getProjectId().toString());
            ps.setString(3, y.getFilename());
            ps.setString(4, y.getDefinitionType().name());
            ps.setString(5, y.getContent());
            ps.setTimestamp(6, Timestamp.from(y.getCreatedAt()));
            ps.executeUpdate();
        }
    }

    public void deleteYamls(UUID projectId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM yaml_definitions WHERE project_id=?")) {
            ps.setString(1, projectId.toString());
            ps.executeUpdate();
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Project queryProject(Connection conn, UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM projects WHERE id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapProject(rs) : null;
            }
        }
    }

    private List<ProjectFile> loadFiles(Connection conn, UUID projectId, boolean withContent)
            throws SQLException {
        String sql = withContent
            ? "SELECT * FROM project_files WHERE project_id=? ORDER BY created_at ASC"
            : "SELECT id, project_id, filename, file_type, size_bytes, created_at" +
              " FROM project_files WHERE project_id=? ORDER BY created_at ASC";
        List<ProjectFile> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ProjectFile f = new ProjectFile();
                    f.setId(UUID.fromString(rs.getString("id")));
                    f.setProjectId(UUID.fromString(rs.getString("project_id")));
                    f.setFilename(rs.getString("filename"));
                    f.setFileType(FileType.valueOf(rs.getString("file_type")));
                    f.setSizeBytes(rs.getLong("size_bytes"));
                    f.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    if (withContent) f.setContent(rs.getBytes("content"));
                    list.add(f);
                }
            }
        }
        return list;
    }

    private List<YamlDefinition> loadYamls(Connection conn, UUID projectId, boolean withContent)
            throws SQLException {
        String sql = withContent
            ? "SELECT * FROM yaml_definitions WHERE project_id=? ORDER BY created_at ASC"
            : "SELECT id, project_id, filename, definition_type, created_at" +
              " FROM yaml_definitions WHERE project_id=? ORDER BY created_at ASC";
        List<YamlDefinition> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    YamlDefinition y = new YamlDefinition();
                    y.setId(UUID.fromString(rs.getString("id")));
                    y.setProjectId(UUID.fromString(rs.getString("project_id")));
                    y.setFilename(rs.getString("filename"));
                    y.setDefinitionType(YamlDefinition.DefinitionType.valueOf(
                            rs.getString("definition_type")));
                    y.setCreatedAt(rs.getTimestamp("created_at").toInstant());
                    if (withContent) y.setContent(rs.getString("content"));
                    else y.setContent("");  // non-null placeholder for size calculation
                    list.add(y);
                }
            }
        }
        return list;
    }

    private List<JobExecution> loadExecutions(Connection conn, UUID projectId, boolean latestOnly)
            throws SQLException {
        String sql = latestOnly
            ? "SELECT * FROM job_executions WHERE project_id=? ORDER BY created_at DESC LIMIT 1"
            : "SELECT * FROM job_executions WHERE project_id=? ORDER BY created_at DESC";
        List<JobExecution> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, projectId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(mapExecution(rs));
            }
        }
        return list;
    }

    private List<JobExecution> loadLatestExecution(Connection conn, UUID projectId)
            throws SQLException {
        return loadExecutions(conn, projectId, true);
    }

    private static Project mapProject(ResultSet rs) throws SQLException {
        Project p = new Project();
        p.setId(UUID.fromString(rs.getString("id")));
        p.setName(rs.getString("name"));
        p.setStatus(ProjectStatus.valueOf(rs.getString("status")));
        p.setErrorMessage(rs.getString("error_message"));
        p.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        p.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());
        return p;
    }

    static JobExecution mapExecution(ResultSet rs) throws SQLException {
        JobExecution e = new JobExecution();
        e.setId(UUID.fromString(rs.getString("id")));
        e.setProjectId(UUID.fromString(rs.getString("project_id")));
        e.setStatus(JobExecution.ExecutionStatus.valueOf(rs.getString("status")));
        Timestamp startedAt = rs.getTimestamp("started_at");
        if (startedAt != null) e.setStartedAt(startedAt.toInstant());
        Timestamp completedAt = rs.getTimestamp("completed_at");
        if (completedAt != null) e.setCompletedAt(completedAt.toInstant());
        long dur = rs.getLong("duration_ms");
        if (!rs.wasNull()) e.setDurationMs(dur);
        e.setErrorMessage(rs.getString("error_message"));
        e.setCreatedAt(rs.getTimestamp("created_at").toInstant());
        return e;
    }
}
