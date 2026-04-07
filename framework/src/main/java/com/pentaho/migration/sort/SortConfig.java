package com.pentaho.migration.sort;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

/**
 * Immutable configuration for ExternalMergeSort.
 * Build via SortConfig.builder().
 */
public final class SortConfig {

    /** Comparator that defines sort order across rows. */
    private final Comparator<Row> comparator;

    /**
     * Maximum bytes to accumulate in memory before spilling to a temp chunk file.
     * Default: 256 MB.
     */
    private final long chunkSizeBytes;

    /** Directory where temp chunk files are written. Default: system temp dir. */
    private final Path tempDir;

    /**
     * Maximum number of chunk files merged simultaneously in one pass.
     * If more chunks exist, multiple merge passes are performed.
     * Default: 16.
     */
    private final int maxMergeFiles;

    private SortConfig(Builder b) {
        this.comparator     = b.comparator;
        this.chunkSizeBytes = b.chunkSizeBytes;
        this.tempDir        = b.tempDir;
        this.maxMergeFiles  = b.maxMergeFiles;
    }

    public Comparator<Row> getComparator()  { return comparator; }
    public long getChunkSizeBytes()         { return chunkSizeBytes; }
    public Path getTempDir()                { return tempDir; }
    public int getMaxMergeFiles()           { return maxMergeFiles; }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Comparator<Row> comparator     = Comparator.comparing(r -> r.getString(0));
        private long chunkSizeBytes            = 256L * 1024 * 1024; // 256 MB
        private Path tempDir                   = Paths.get(System.getProperty("java.io.tmpdir"));
        private int maxMergeFiles              = 16;

        public Builder comparator(Comparator<Row> comparator) {
            this.comparator = comparator;
            return this;
        }

        public Builder chunkSizeBytes(long chunkSizeBytes) {
            if (chunkSizeBytes < 1024) throw new IllegalArgumentException("chunkSizeBytes must be >= 1024");
            this.chunkSizeBytes = chunkSizeBytes;
            return this;
        }

        public Builder tempDir(Path tempDir) {
            this.tempDir = tempDir;
            return this;
        }

        public Builder maxMergeFiles(int maxMergeFiles) {
            if (maxMergeFiles < 2) throw new IllegalArgumentException("maxMergeFiles must be >= 2");
            this.maxMergeFiles = maxMergeFiles;
            return this;
        }

        public SortConfig build() {
            if (comparator == null) throw new IllegalStateException("comparator is required");
            return new SortConfig(this);
        }
    }
}
