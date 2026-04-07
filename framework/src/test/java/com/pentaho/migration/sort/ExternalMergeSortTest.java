package com.pentaho.migration.sort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correctness and basic performance tests for ExternalMergeSort.
 *
 * Each test verifies:
 *   1. Output row count == input row count
 *   2. Output is in sorted order (comparator.compare(row[i], row[i+1]) <= 0)
 *   3. All input rows are present in the output (via sorted multiset comparison)
 *
 * Throughput (MB/s) is logged for each parameterised case but is not asserted.
 */
class ExternalMergeSortTest {

    private static final int FIELDS = 3;
    private static final Random RNG = new Random(42);

    private Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("extsort_test_");
    }

    @AfterEach
    void tearDown() throws IOException {
        // Clean up any leftover temp files from the test run.
        if (Files.exists(tempDir)) {
            try (var stream = Files.walk(tempDir)) {
                stream.sorted(Comparator.reverseOrder())
                      .forEach(p -> {
                          try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                      });
            }
        }
    }

    // -------------------------------------------------------------------------
    // Parameterised correctness + throughput tests
    // -------------------------------------------------------------------------

    record TestCase(String name, int rowCount, long chunkSizeBytes, int maxMergeFiles) {
        @Override public String toString() { return name; }
    }

    static Stream<TestCase> testCases() {
        return Stream.of(
            // 1 chunk — exercises pure in-memory sort path
            new TestCase("tinyInMemory",   100,   256L * 1024 * 1024, 16),
            // ~6 chunks — exercises basic multi-chunk merge
            new TestCase("smallForced",    10_000,  50L * 1024,        16),
            // ~125 chunks, maxMergeFiles=4 — exercises multi-pass merge
            new TestCase("mediumForced",   200_000, 100L * 1024,        4),
            // ~312 chunks — stress test at scale
            new TestCase("largeForced",    500_000, 100L * 1024,        8)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    void testSortCorrectness(TestCase tc) throws IOException {
        List<Row> input = generateRandomRows(tc.rowCount(), FIELDS);

        SortConfig config = SortConfig.builder()
                .comparator(nullSafeComparator())
                .chunkSizeBytes(tc.chunkSizeBytes())
                .tempDir(tempDir)
                .maxMergeFiles(tc.maxMergeFiles())
                .build();

        long startMs = System.currentTimeMillis();
        Iterator<Row> result = new ExternalMergeSort(config).sort(input.iterator());
        List<Row> output = drain(result);
        long elapsedMs = System.currentTimeMillis() - startMs;

        assertCount(tc.rowCount(), output);
        assertSorted(output, config.getComparator());
        assertSameRows(input, output);

        logThroughput(tc.name(), output, elapsedMs);
    }

    // -------------------------------------------------------------------------
    // Specialised correctness tests
    // -------------------------------------------------------------------------

    @Test
    void testAlreadySorted() throws IOException {
        List<Row> input = generateRandomRows(50_000, FIELDS);
        input.sort(nullSafeComparator());

        SortConfig config = sortConfig(100 * 1024, 16);
        List<Row> output = drain(new ExternalMergeSort(config).sort(input.iterator()));

        assertCount(50_000, output);
        assertSorted(output, config.getComparator());
        assertSameRows(input, output);
    }

    @Test
    void testReverseSorted() throws IOException {
        List<Row> input = generateRandomRows(50_000, FIELDS);
        input.sort(nullSafeComparator().reversed());

        SortConfig config = sortConfig(100 * 1024, 16);
        List<Row> output = drain(new ExternalMergeSort(config).sort(input.iterator()));

        assertCount(50_000, output);
        assertSorted(output, config.getComparator());
        assertSameRows(input, output);
    }

    @Test
    void testAllDuplicates() throws IOException {
        String[] fixed = {"ALPHA", "BETA", "GAMMA"};
        List<Row> input = new ArrayList<>(50_000);
        for (int i = 0; i < 50_000; i++) input.add(new Row(fixed.clone()));

        SortConfig config = sortConfig(100 * 1024, 16);
        List<Row> output = drain(new ExternalMergeSort(config).sort(input.iterator()));

        assertCount(50_000, output);
        assertSorted(output, config.getComparator());
        // Every output row must equal the fixed row
        output.forEach(row -> assertArrayEquals(fixed, row.getValues()));
    }

    @Test
    void testWithNulls() throws IOException {
        List<Row> input = generateRowsWithNulls(10_000, FIELDS);

        SortConfig config = sortConfig(100 * 1024, 16);
        List<Row> output = drain(new ExternalMergeSort(config).sort(input.iterator()));

        assertCount(10_000, output);
        assertSorted(output, config.getComparator());
        assertSameRows(input, output);
    }

    @Test
    void testEmptyInput() throws IOException {
        SortConfig config = sortConfig(100 * 1024, 16);
        Iterator<Row> result = new ExternalMergeSort(config).sort(Collections.emptyIterator());
        assertFalse(result.hasNext());
    }

    @Test
    void testSingleRow() throws IOException {
        List<Row> input = List.of(new Row(new String[]{"only", "one", "row"}));
        SortConfig config = sortConfig(100 * 1024, 16);
        List<Row> output = drain(new ExternalMergeSort(config).sort(input.iterator()));

        assertCount(1, output);
        assertEquals(input.get(0), output.get(0));
    }

    // -------------------------------------------------------------------------
    // Serialization round-trip
    // -------------------------------------------------------------------------

    @Test
    void testSerializerRoundTrip() {
        Row original = new Row(new String[]{"hello", null, "world", "", "unicode-\u00e9"});
        int size = RowSerializer.serializedSize(original);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(size);
        RowSerializer.write(buf, original);
        buf.flip();
        Row restored = RowSerializer.read(buf);

        assertArrayEquals(original.getValues(), restored.getValues());
    }

    @Test
    void testSerializerAllNulls() {
        Row original = new Row(new String[]{null, null, null});
        int size = RowSerializer.serializedSize(original);

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(size);
        RowSerializer.write(buf, original);
        buf.flip();
        Row restored = RowSerializer.read(buf);

        assertArrayEquals(original.getValues(), restored.getValues());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SortConfig sortConfig(long chunkSizeBytes, int maxMergeFiles) {
        return SortConfig.builder()
                .comparator(nullSafeComparator())
                .chunkSizeBytes(chunkSizeBytes)
                .tempDir(tempDir)
                .maxMergeFiles(maxMergeFiles)
                .build();
    }

    private static Comparator<Row> nullSafeComparator() {
        return (a, b) -> {
            String va = a.getString(0);
            String vb = b.getString(0);
            if (va == null && vb == null) return 0;
            if (va == null) return -1;
            if (vb == null) return 1;
            return va.compareTo(vb);
        };
    }

    private static List<Row> generateRandomRows(int count, int fields) {
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String[] values = new String[fields];
            for (int f = 0; f < fields; f++) {
                values[f] = randomString(8 + RNG.nextInt(12));
            }
            rows.add(new Row(values));
        }
        return rows;
    }

    private static List<Row> generateRowsWithNulls(int count, int fields) {
        List<Row> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String[] values = new String[fields];
            for (int f = 0; f < fields; f++) {
                values[f] = RNG.nextInt(5) == 0 ? null : randomString(8 + RNG.nextInt(12));
            }
            rows.add(new Row(values));
        }
        return rows;
    }

    private static String randomString(int length) {
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(RNG.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static List<Row> drain(Iterator<Row> iter) {
        List<Row> result = new ArrayList<>();
        while (iter.hasNext()) result.add(iter.next());
        return result;
    }

    // -------------------------------------------------------------------------
    // Assertions
    // -------------------------------------------------------------------------

    private static void assertCount(int expected, List<Row> output) {
        assertEquals(expected, output.size(),
                "Row count mismatch: expected " + expected + " but got " + output.size());
    }

    private static void assertSorted(List<Row> output, Comparator<Row> cmp) {
        for (int i = 1; i < output.size(); i++) {
            int c = cmp.compare(output.get(i - 1), output.get(i));
            assertTrue(c <= 0,
                    "Sort order violated at index " + i + ": "
                    + output.get(i - 1) + " > " + output.get(i));
        }
    }

    /**
     * Verifies that the output contains exactly the same rows as the input,
     * using a sorted list comparison to handle duplicates correctly.
     */
    private static void assertSameRows(List<Row> input, List<Row> output) {
        Comparator<Row> cmp = Comparator.comparing(Row::toString);
        List<Row> sortedInput  = new ArrayList<>(input);
        List<Row> sortedOutput = new ArrayList<>(output);
        sortedInput.sort(cmp);
        sortedOutput.sort(cmp);

        assertEquals(sortedInput.size(), sortedOutput.size(), "Row counts differ");
        for (int i = 0; i < sortedInput.size(); i++) {
            assertArrayEquals(
                    sortedInput.get(i).getValues(),
                    sortedOutput.get(i).getValues(),
                    "Row mismatch at sorted index " + i);
        }
    }

    // -------------------------------------------------------------------------
    // Performance logging
    // -------------------------------------------------------------------------

    private static void logThroughput(String name, List<Row> output, long elapsedMs) {
        long totalBytes = 0;
        for (Row row : output) {
            totalBytes += RowSerializer.serializedSize(row);
        }
        double mb = totalBytes / (1024.0 * 1024.0);
        double mbPerSec = elapsedMs > 0 ? mb / (elapsedMs / 1000.0) : Double.MAX_VALUE;
        System.out.printf("[%s] rows=%,d  data=%.1f MB  elapsed=%d ms  throughput=%.1f MB/s%n",
                name, output.size(), mb, elapsedMs, mbPerSec);
    }
}
