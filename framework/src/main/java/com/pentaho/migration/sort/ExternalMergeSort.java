package com.pentaho.migration.sort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * Disk-based external merge sort for large datasets using MappedByteBuffer temp files.
 *
 * Algorithm:
 *   Phase 1 — Split &amp; Sort
 *     Read input rows, accumulate in memory until chunkSizeBytes is reached,
 *     sort the in-memory chunk, spill to a TempChunkFile. Repeat for all input.
 *
 *   Phase 2 — Merge
 *     Perform one or more merge passes until ≤ maxMergeFiles chunks remain,
 *     then return a streaming k-way merge iterator directly to the caller
 *     (no final disk write).
 *
 * Memory usage:
 *   At most chunkSizeBytes bytes of row data are held in memory at any time
 *   during phase 1. During phase 2, only one row per open chunk is held in
 *   the priority queue heap.
 *
 * Temp file cleanup:
 *   Each TempChunkFile is deleted as soon as it is no longer needed.
 *   The final merge iterator deletes its backing files when fully consumed.
 *   Callers that abandon the iterator mid-stream should not rely on automatic
 *   cleanup — the files will remain until JVM exit in that case.
 */
public final class ExternalMergeSort {

    private final SortConfig config;

    public ExternalMergeSort(SortConfig config) {
        this.config = config;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Sorts all rows produced by {@code input} and returns a lazy iterator
     * over the sorted result. Rows are processed in a single pass over the
     * input; the result iterator drives the final merge on demand.
     *
     * @param input source rows — consumed once, may be very large
     * @return sorted rows as a pull-based iterator (backpressure-safe)
     * @throws IOException if any temp file operation fails
     */
    public Iterator<Row> sort(Iterator<Row> input) throws IOException {
        List<TempChunkFile> chunks = splitAndSort(input);

        if (chunks.isEmpty()) {
            return Collections.emptyIterator();
        }
        if (chunks.size() == 1) {
            return chunks.get(0).reader();
        }

        chunks = multiPassMerge(chunks);
        return finalMergeIterator(chunks);
    }

    // -------------------------------------------------------------------------
    // Phase 1: Split & Sort
    // -------------------------------------------------------------------------

    private List<TempChunkFile> splitAndSort(Iterator<Row> input) throws IOException {
        List<TempChunkFile> chunks = new ArrayList<>();
        List<Row> currentChunk = new ArrayList<>();
        long currentChunkBytes = 0;

        while (input.hasNext()) {
            Row row = input.next();
            int rowBytes = RowSerializer.serializedSize(row);
            currentChunk.add(row);
            currentChunkBytes += rowBytes;

            if (currentChunkBytes >= config.getChunkSizeBytes()) {
                chunks.add(flushChunk(currentChunk, currentChunkBytes));
                currentChunk = new ArrayList<>();
                currentChunkBytes = 0;
            }
        }

        if (!currentChunk.isEmpty()) {
            chunks.add(flushChunk(currentChunk, currentChunkBytes));
        }

        return chunks;
    }

    /**
     * @param chunkBytes sum of RowSerializer.serializedSize() for every row in the chunk,
     *                   already computed during accumulation — avoids a second scan.
     */
    private TempChunkFile flushChunk(List<Row> rows, long chunkBytes) throws IOException {
        rows.sort(config.getComparator());
        long totalFileSize = 8L + chunkBytes; // 8-byte row-count header + row data
        return TempChunkFile.write(rows, totalFileSize, config.getTempDir());
    }

    // -------------------------------------------------------------------------
    // Phase 2: Multi-pass merge
    // -------------------------------------------------------------------------

    /**
     * Repeatedly merges groups of maxMergeFiles chunks into single chunks until
     * at most maxMergeFiles chunks remain. The remaining chunks are returned for
     * the final in-memory streaming merge.
     */
    private List<TempChunkFile> multiPassMerge(List<TempChunkFile> chunks) throws IOException {
        while (chunks.size() > config.getMaxMergeFiles()) {
            List<TempChunkFile> nextRound = new ArrayList<>();
            int i = 0;
            while (i < chunks.size()) {
                int end = Math.min(i + config.getMaxMergeFiles(), chunks.size());
                List<TempChunkFile> batch = chunks.subList(i, end);
                if (batch.size() == 1) {
                    // Nothing to merge — carry the single chunk forward.
                    nextRound.add(batch.get(0));
                } else {
                    nextRound.add(mergeChunksToFile(batch));
                    // Delete input chunks now that they are merged.
                    batch.forEach(TempChunkFile::delete);
                }
                i = end;
            }
            chunks = nextRound;
        }
        return chunks;
    }

    /**
     * Merges a batch of chunks into one new TempChunkFile.
     * The output file size is computed exactly from the input file sizes so we
     * pre-allocate a single MappedByteBuffer with no resizing.
     *
     * Math:
     *   Each input file = 8 (header) + rowBytes
     *   Output file     = 8 (header) + sum(rowBytes across all inputs)
     *                   = sum(inputFileSize) - 8 * (inputCount - 1)
     */
    private TempChunkFile mergeChunksToFile(List<TempChunkFile> inputChunks) throws IOException {
        long totalRowCount   = 0;
        long outputFileSize  = 8L; // output header
        for (TempChunkFile chunk : inputChunks) {
            totalRowCount  += chunk.rowCount();
            outputFileSize += chunk.fileSize() - 8L; // subtract input header
        }

        List<Iterator<Row>> readers = new ArrayList<>(inputChunks.size());
        for (TempChunkFile chunk : inputChunks) {
            readers.add(chunk.reader());
        }

        Iterator<Row> merged = buildHeapIterator(readers);
        return TempChunkFile.writeFromIterator(merged, totalRowCount, outputFileSize,
                                               config.getTempDir());
    }

    // -------------------------------------------------------------------------
    // Final streaming merge — no disk write
    // -------------------------------------------------------------------------

    /**
     * Returns a streaming iterator that k-way merges the final set of chunks.
     * No data is materialised in memory beyond the current row from each chunk.
     * The backing temp files are deleted as each chunk reader is exhausted.
     */
    private Iterator<Row> finalMergeIterator(List<TempChunkFile> chunks) throws IOException {
        List<Iterator<Row>> readers = new ArrayList<>(chunks.size());
        for (TempChunkFile chunk : chunks) {
            readers.add(chunk.reader());
        }
        return buildHeapIterator(readers);
    }

    // -------------------------------------------------------------------------
    // K-way heap merge
    // -------------------------------------------------------------------------

    private Iterator<Row> buildHeapIterator(List<Iterator<Row>> readers) {
        Comparator<Row> cmp = config.getComparator();

        // Each heap entry: [row, readerIndex]
        // We use a simple mutable array to avoid creating many small objects.
        PriorityQueue<ChunkCursor> heap = new PriorityQueue<>(
                Math.max(1, readers.size()),
                (a, b) -> cmp.compare(a.current, b.current));

        for (Iterator<Row> reader : readers) {
            if (reader.hasNext()) {
                heap.offer(new ChunkCursor(reader, reader.next()));
            }
        }

        return new Iterator<Row>() {
            @Override
            public boolean hasNext() {
                return !heap.isEmpty();
            }

            @Override
            public Row next() {
                if (!hasNext()) throw new NoSuchElementException();
                ChunkCursor top = heap.poll();
                Row result = top.current;
                if (top.iter.hasNext()) {
                    top.current = top.iter.next();
                    heap.offer(top);
                }
                return result;
            }
        };
    }

    // -------------------------------------------------------------------------
    // Internal types
    // -------------------------------------------------------------------------

    private static final class ChunkCursor {
        Iterator<Row> iter;
        Row current;

        ChunkCursor(Iterator<Row> iter, Row current) {
            this.iter    = iter;
            this.current = current;
        }
    }
}
