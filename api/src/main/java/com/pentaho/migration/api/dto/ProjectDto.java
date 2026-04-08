package com.pentaho.migration.api.dto;

import com.pentaho.migration.api.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full project detail — returned by GET /api/projects/{id} and POST /api/projects/{id}/convert. */
public record ProjectDto(
        UUID id,
        String name,
        String status,
        String errorMessage,
        List<FileInfo> files,
        List<YamlInfo> yamlDefinitions,
        List<JobExecutionDto> executions,
        Instant createdAt,
        Instant updatedAt
) {
    public record FileInfo(UUID id, String filename, String fileType, long sizeBytes) {}
    public record YamlInfo(UUID id, String filename, String definitionType, int contentLength, String content) {}

    public static ProjectDto from(Project p) {
        return new ProjectDto(
                p.getId(),
                p.getName(),
                p.getStatus().name(),
                p.getErrorMessage(),
                p.getFiles().stream()
                        .map(f -> new FileInfo(f.getId(), f.getFilename(),
                                f.getFileType().name(), f.getSizeBytes()))
                        .toList(),
                p.getYamlDefinitions().stream()
                        .map(y -> new YamlInfo(y.getId(), y.getFilename(),
                                y.getDefinitionType().name(),
                                y.getContent().length(), y.getContent()))
                        .toList(),
                p.getExecutions().stream().map(JobExecutionDto::from).toList(),
                p.getCreatedAt(),
                p.getUpdatedAt()
        );
    }
}
