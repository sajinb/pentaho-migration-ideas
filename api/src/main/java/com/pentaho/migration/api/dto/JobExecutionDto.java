package com.pentaho.migration.api.dto;

import com.pentaho.migration.api.domain.JobExecution;

import java.time.Instant;
import java.util.UUID;

public record JobExecutionDto(
        UUID id,
        UUID projectId,
        String status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String errorMessage,
        Instant createdAt
) {
    public static JobExecutionDto from(JobExecution e) {
        return new JobExecutionDto(
                e.getId(),
                e.getProject().getId(),
                e.getStatus().name(),
                e.getStartedAt(),
                e.getCompletedAt(),
                e.getDurationMs(),
                e.getErrorMessage(),
                e.getCreatedAt()
        );
    }
}
