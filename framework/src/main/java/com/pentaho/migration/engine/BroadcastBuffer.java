package com.pentaho.migration.engine;

import com.pentaho.migration.sort.Row;
import com.pentaho.migration.sort.RowSerializer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Materializes an upstream {@code Iterator<Row>} to a temp file, then serves N independent
 * readers (one per fan-out branch). The temp file is deleted when all readers are exhausted.
 *
 * <p>Write phase: uses {@code FileChannel} + heap {@code ByteBuffer} (no pre-computed size needed).
 * A 8-byte row count is written as a header at position 0 after all rows are written.
 *
 * <p>Read phase: each reader uses a {@code MappedByteBuffer} (zero-copy).
 * On reader exhaustion, an {@code AtomicInteger} ref-count is decremented;
 * when it reaches zero the file is deleted.
 *
 * <p>No {@code force()} / msync is called on the write-side FileChannel — same-JVM read
 * visibility is guaranteed by the OS page cache (same reasoning as in TempChunkFile).
 */
public final class BroadcastBuffer {

    private static final int IO_BUFFER_SIZE = 64 * 1024; // 64 KB write buffer

    private final Path          file;
    private final long          rowCount;
    private final long          fileSize;
    private final AtomicInteger refCount;

    private BroadcastBuffer(Path file, long rowCount, long fileSize, int branchCount) {
        this.file     = file;
        this.rowCount = rowCount;
        this.fileSize = fileSize;
        this.refCount = new AtomicInteger(branchCount);
    }

    /**
     * Drains {@code upstream} to a temp file in {@code tempDir} and returns a
     * {@code BroadcastBuffer} ready to serve {@code branchCount} independent readers.
     */
    public static BroadcastBuffer materialize(Iterator<Row> upstream,
                                               int branchCount,
                                               Path tempDir) throws IOException {
        Path file = Files.createTempFile(tempDir, "broadcast-", ".tmp");

        long rowCount;
        long dataSize;

        try (FileChannel channel = FileChannel.open(file,
                StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            // Reserve 8 bytes for row count header (written at the end)
            channel.position(8);

            ByteBuffer buf   = ByteBuffer.allocate(IO_BUFFER_SIZE);
            long       count = 0;

            while (upstream.hasNext()) {
                Row    row         = upstream.next();
                int    rowBytes    = RowSerializer.serializedSize(row);

                // Flush if row doesn't fit in buffer
                if (buf.position() + rowBytes > buf.capacity()) {
                    buf.flip();
                    while (buf.hasRemaining()) channel.write(buf);
                    buf.clear();
                }

                // If row is larger than the buffer, write directly
                if (rowBytes > buf.capacity()) {
                    ByteBuffer direct = ByteBuffer.allocate(rowBytes);
                    RowSerializer.write(direct, row);
                    direct.flip();
                    while (direct.hasRemaining()) channel.write(direct);
                } else {
                    RowSerializer.write(buf, row);
                }
                count++;
            }

            // Flush remaining bytes
            if (buf.position() > 0) {
                buf.flip();
                while (buf.hasRemaining()) channel.write(buf);
            }

            dataSize = channel.position() - 8;

            // Write row count at position 0
            ByteBuffer header = ByteBuffer.allocate(8);
            header.putLong(count);
            header.flip();
            channel.position(0);
            while (header.hasRemaining()) channel.write(header);

            rowCount = count;
        }

        long fileSize = 8 + dataSize;
        return new BroadcastBuffer(file, rowCount, fileSize, branchCount);
    }

    /**
     * Returns a new independent {@code Iterator<Row>} for branch {@code branchIndex}.
     * All branches read from the same underlying file.
     * The file is deleted when the last reader is exhausted.
     *
     * @param branchIndex 0-based branch index (for logging only; all readers see all rows)
     */
    public Iterator<Row> readerForBranch(int branchIndex) throws IOException {
        if (rowCount == 0) {
            refCount.decrementAndGet(); // nothing to read; release immediately
            return java.util.Collections.emptyIterator();
        }

        MappedByteBuffer mapped;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            mapped = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
        }
        // Skip the 8-byte row-count header
        mapped.position(8);

        final long total = rowCount;
        return new Iterator<Row>() {
            private long remaining = total;
            private boolean released = false;

            @Override
            public boolean hasNext() {
                if (remaining > 0) return true;
                release();
                return false;
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                remaining--;
                return RowSerializer.read(mapped);
            }

            private void release() {
                if (!released) {
                    released = true;
                    if (refCount.decrementAndGet() == 0) {
                        try { Files.deleteIfExists(file); } catch (IOException ignored) {}
                    }
                }
            }
        };
    }
}
