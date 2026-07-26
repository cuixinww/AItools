package com.dg.tools.extractor.excel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CsvSlicerTest {

    @TempDir
    Path tempDir;

    @Test
    void sliceNotNeededBelowThreshold() throws IOException {
        List<List<String>> rows = List.of(
                List.of("a", "b"),
                List.of("c", "d")
        );
        CsvSlicer.sliceIfNeeded(tempDir, "test", rows, List.of("col1", "col2"));
        assertThat(tempDir.resolve("test")).doesNotExist();
    }

    @Test
    void sliceExactlyAtThreshold() throws IOException {
        List<List<String>> rows = createRows(500);
        CsvSlicer.sliceIfNeeded(tempDir, "exact", rows, List.of("col1"));
        assertThat(tempDir.resolve("exact")).doesNotExist();
    }

    @Test
    void sliceAboveThreshold() throws IOException {
        List<List<String>> rows = createRows(550);
        CsvSlicer.sliceIfNeeded(tempDir, "large", rows, List.of("col1"));
        assertThat(tempDir.resolve("large")).isDirectory();
    }

    @Test
    void sliceIndexJsonContent() throws IOException {
        List<List<String>> rows = createRows(550);
        CsvSlicer.sliceIfNeeded(tempDir, "verify", rows, List.of("col1"));

        Path indexFile = tempDir.resolve("verify/index.json");
        assertThat(indexFile).exists();
        String content = Files.readString(indexFile);
        assertThat(content).contains("version")
                .contains("total_rows")
                .contains("chunks");
    }

    @Test
    void sliceChunkFilesExist() throws IOException {
        List<List<String>> rows = createRows(550);
        CsvSlicer.sliceIfNeeded(tempDir, "chunks", rows, List.of("col1"));

        Path sliceDir = tempDir.resolve("chunks");
        assertThat(sliceDir.resolve("chunk_0001.csv")).exists();
        assertThat(sliceDir.resolve("chunk_0006.csv")).exists();
        assertThat(sliceDir.resolve("chunk_0007.csv")).doesNotExist();
    }

    @Test
    void sliceCleansUpOldFiles() throws IOException {
        Path sliceDir = tempDir.resolve("clean");
        Files.createDirectories(sliceDir);
        Path oldFile = sliceDir.resolve("old.csv");
        Files.writeString(oldFile, "old data");

        List<List<String>> rows = createRows(550);
        CsvSlicer.sliceIfNeeded(tempDir, "clean", rows, List.of("col1"));

        assertThat(oldFile).doesNotExist();
    }

    @Test
    void sliceWithSpecialCharsInSheetName() throws IOException {
        List<List<String>> rows = createRows(550);
        CsvSlicer.sliceIfNeeded(tempDir, "my sheet:test", rows, List.of("col1"));

        assertThat(tempDir.resolve("my_sheet_test")).isDirectory();
    }

    @Test
    void sliceExactMultipleOfChunkSize() throws IOException {
        List<List<String>> rows = createRows(600);
        CsvSlicer.sliceIfNeeded(tempDir, "exact6", rows, List.of("col1"));

        assertThat(tempDir.resolve("exact6/chunk_0006.csv")).exists();
        assertThat(tempDir.resolve("exact6/chunk_0007.csv")).doesNotExist();
    }

    private static List<List<String>> createRows(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> List.of("row_" + i))
                .toList();
    }
}
