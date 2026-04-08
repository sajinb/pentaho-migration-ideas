package com.pentaho.migration.step.impl.source;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.step.AbstractSourceStep;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

/**
 * Lists files matching a glob pattern and emits one row per file.
 * Equivalent to Pentaho's GetFileNames step.
 *
 * <p>Emitted fields: [filePath, fileName, fileSize, lastModified]
 *
 * <p>Params:
 * <ul>
 *   <li>{@code directory} — directory to search (required)
 *   <li>{@code glob}      — glob pattern, default "*" (all files)
 * </ul>
 */
public class GetFileNamesStep extends AbstractSourceStep {

    private String directory;
    private String glob = "*";

    @Override
    public void configure(Map<String, String> params) {
        directory = params.get("directory");
        glob      = params.getOrDefault("glob", "*");
    }

    @Override
    protected Iterator<Row> readRows() throws IOException {
        Path dir = Paths.get(directory);
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        List<Row> rows = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> matcher.matches(p.getFileName()))
                 .forEach(p -> {
                     try {
                         BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                         rows.add(new Row(new String[]{
                             p.toAbsolutePath().toString(),
                             p.getFileName().toString(),
                             String.valueOf(attrs.size()),
                             attrs.lastModifiedTime().toInstant().toString()
                         }));
                     } catch (IOException e) {
                         throw new RuntimeException(e);
                     }
                 });
        }
        return rows.iterator();
    }
}
