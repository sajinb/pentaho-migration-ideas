package com.pentaho.migration.sort;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * A single sorted chunk written to a temporary file using MappedByteBuffer.
 *
 * File layout:
 *   [8 bytes: row count (long)]
 *   [serialized rows ... ]
 *
 * Write path  — pre-allocates the exact file size so one MappedByteBuffer covers
 *               the whole file with no resizing.
 * Read path   — maps the file READ_ONLY; the iterator deletes the file automatically
 *               when the last row is consumed.
 *
 * The file size can be queried via fileSize() and the row count via rowCount()
 * without opening a full row iterator. ExternalMergeSort uses these to compute
 * the exact output size for intermediate merge files.
 */
public final class TempChunkFile {

    private final Path file;

    TempChunkFile(Path file) {
        this.file = file;
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Rows are already sorted by the caller. {@code totalFileSize} must equal
     * {@code 8 + sum(RowSerializer.serializedSize(row)) for row in sortedRows} —
     * the caller pre-computes this during accumulation so we avoid a second scan.
     *
     * buf.force() is intentionally omitted: we are writing a temp file that is
     * read back in the same JVM on the same OS. The page cache guarantees that
     * a subsequent READ mapping sees the same (dirty) pages without a disk flush.
     */
    public static TempChunkFile write(List<Row> sortedRows, long totalFileSize,
                                      Path tempDir) throws IOException {
        Path file = Files.createTempFile(tempDir, "extsort_", ".tmp");

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            raf.setLength(totalFileSize);
            MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalFileSize);
            buf.putLong(sortedRows.size());
            for (Row row : sortedRows) {
                RowSerializer.write(buf, row);
            }
            // No buf.force() — see Javadoc above
        }

        return new TempChunkFile(file);
    }

    /**
     * Writes a merged stream directly to a new temp file. The total row count and
     * exact byte size must be provided upfront so we can pre-allocate the mapping.
     * outputFileSize = 8 + sum(serializedSize(row) for every row in rows).
     *
     * Callers derive outputFileSize as:
     *   sum(inputChunk.fileSize()) - 8 * (inputChunks.size() - 1)
     * because the row-byte content is identical across re-serialization.
     */
    static TempChunkFile writeFromIterator(Iterator<Row> rows,
                                           long rowCount,
                                           long outputFileSize,
                                           Path tempDir) throws IOException {
        Path file = Files.createTempFile(tempDir, "extsort_merge_", ".tmp");

        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
             FileChannel channel = raf.getChannel()) {
            raf.setLength(outputFileSize);
            MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, outputFileSize);
            buf.putLong(rowCount);
            while (rows.hasNext()) {
                RowSerializer.write(buf, rows.next());
            }
            // No buf.force() — same-JVM page-cache guarantee applies here too
        }

        return new TempChunkFile(file);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Returns a lazy iterator over all rows in this chunk. The backing temp file
     * is deleted automatically when the iterator is fully consumed.
     * If the iterator is abandoned mid-stream, call delete() explicitly.
     */
    public Iterator<Row> reader() throws IOException {
        long fileSize = Files.size(file);
        // Channel can be closed immediately after mapping; the MappedByteBuffer
        // keeps the mapping alive independently (OS page-cache backed).
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        MappedByteBuffer buf;
        try {
            buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        } finally {
            channel.close();
        }

        long rowCount = buf.getLong();

        return new Iterator<Row>() {
            private long remaining = rowCount;
            private boolean deleted = false;

            @Override
            public boolean hasNext() {
                if (remaining > 0) return true;
                if (!deleted) {
                    deleted = true;
                    delete();
                }
                return false;
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                remaining--;
                return RowSerializer.read(buf);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Metadata — readable without consuming rows
    // -------------------------------------------------------------------------

    /**
     * Returns the total file size in bytes. Used by ExternalMergeSort to compute
     * the exact output size for intermediate merge files without reading row data.
     */
    public long fileSize() throws IOException {
        return Files.size(file);
    }

    /**
     * Returns the row count stored in the file header. Used by ExternalMergeSort
     * to compute the merged output row count.
     */
    public long rowCount() throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, 8);
            return buf.getLong();
        }
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /** Deletes the backing temp file. Safe to call multiple times. */
    public void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // On Linux, unlink succeeds even if a mapping is still live.
            // Silently ignore — worst case the OS cleans up on JVM exit.
        }
    }

    public Path getFile() {
        return file;
    }
}
