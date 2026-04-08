package com.pentaho.migration.api.repository;

import com.pentaho.migration.api.config.DatabaseConfig;
import com.pentaho.migration.api.domain.JobExecution;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class JobExecutionDao {

    private final DatabaseConfig db;

    public JobExecutionDao(DatabaseConfig db) { this.db = db; }

    public JobExecution insert(JobExecution e) throws SQLException {
        if (e.getId() == null) e.setId(UUID.randomUUID());
        e.setCreatedAt(Instant.now());

        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO job_executions" +
                " (id, project_id, status, started_at, completed_at, duration_ms, error_message, created_at)" +
                " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, e.getId().toString());
            ps.setString(2, e.getProjectId().toString());
            ps.setString(3, e.getStatus().name());
            setNullableTimestamp(ps, 4, e.getStartedAt());
            setNullableTimestamp(ps, 5, e.getCompletedAt());
            if (e.getDurationMs() != null) ps.setLong(6, e.getDurationMs());
            else ps.setNull(6, Types.BIGINT);
            ps.setString(7, e.getErrorMessage());
            ps.setTimestamp(8, Timestamp.from(e.getCreatedAt()));
            ps.executeUpdate();
        }
        return e;
    }

    public void update(JobExecution e) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "UPDATE job_executions" +
                " SET status=?, started_at=?, completed_at=?, duration_ms=?, error_message=?" +
                " WHERE id=?")) {
            ps.setString(1, e.getStatus().name());
            setNullableTimestamp(ps, 2, e.getStartedAt());
            setNullableTimestamp(ps, 3, e.getCompletedAt());
            if (e.getDurationMs() != null) ps.setLong(4, e.getDurationMs());
            else ps.setNull(4, Types.BIGINT);
            ps.setString(5, e.getErrorMessage());
            ps.setString(6, e.getId().toString());
            ps.executeUpdate();
        }
    }

    public Optional<JobExecution> findById(UUID id) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM job_executions WHERE id=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ProjectDao.mapExecution(rs)) : Optional.empty();
            }
        }
    }

    public List<JobExecution> findByProjectId(UUID projectId) throws SQLException {
        List<JobExecution> list = new ArrayList<>();
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM job_executions WHERE project_id=? ORDER BY created_at DESC")) {
            ps.setString(1, projectId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(ProjectDao.mapExecution(rs));
            }
        }
        return list;
    }

    public Optional<JobExecution> findByIdAndProjectId(UUID id, UUID projectId) throws SQLException {
        try (Connection conn = db.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM job_executions WHERE id=? AND project_id=?")) {
            ps.setString(1, id.toString());
            ps.setString(2, projectId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(ProjectDao.mapExecution(rs)) : Optional.empty();
            }
        }
    }

    private static void setNullableTimestamp(PreparedStatement ps, int idx, Instant instant)
            throws SQLException {
        if (instant != null) ps.setTimestamp(idx, Timestamp.from(instant));
        else ps.setNull(idx, Types.TIMESTAMP);
    }
}
