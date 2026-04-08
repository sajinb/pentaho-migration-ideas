package com.pentaho.migration.api.repository;

import com.pentaho.migration.api.domain.JobExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobExecutionRepository extends JpaRepository<JobExecution, UUID> {

    List<JobExecution> findByProjectIdOrderByCreatedAtDesc(UUID projectId);

    Optional<JobExecution> findByIdAndProjectId(UUID id, UUID projectId);
}
