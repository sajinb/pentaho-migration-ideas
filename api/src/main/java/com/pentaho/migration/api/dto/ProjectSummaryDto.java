package com.pentaho.migration.api.dto;

import com.pentaho.migration.api.domain.Project;

import java.time.Instant;
import java.util.UUID;

/** Lightweight project row — returned in GET /api/projects list. */
public record ProjectSummaryDto(
        UUID id,
        String name,
        String status,
        int fileCount,
        int yamlCount,
        JobExecutionDto latestExecution,
        Instant createdAt
) {
    public static ProjectSummaryDto from(Project p) {
        JobExecutionDto latest = p.getExecutions().isEmpty()
                ? null
                : JobExecutionDto.from(p.getExecutions().get(0)); // ordered DESC

        return new ProjectSummaryDto(
                p.getId(),
                p.getName(),
                p.getStatus().name(),
                p.getFiles().size(),
                p.getYamlDefinitions().size(),
                latest,
                p.getCreatedAt()
        );
    }
}
