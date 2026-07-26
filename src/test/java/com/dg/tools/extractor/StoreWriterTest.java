package com.dg.tools.extractor;

import com.dg.tools.extractor.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StoreWriterTest {

    @TempDir
    Path tempDir;

    private StoreWriter writer;
    private ExtractionResult result;

    @BeforeEach
    void setUp() {
        writer = new StoreWriter();
        result = ExtractionResult.of("test", "file.txt");
    }

    @Test
    void writeBodyWithoutParent() throws Exception {
        result.addElement(new Element(0, "paragraph", "hello world"));
        writer.writeBody(tempDir, "body.md", result, 1, "source.txt", null);
        Path bodyFile = tempDir.resolve("body.md");
        assertThat(bodyFile).exists();
        String content = Files.readString(bodyFile);
        assertThat(content).contains("DOC: 1");
        assertThat(content).contains("SOURCE: source.txt");
        assertThat(content).doesNotContain("PARENT");
        assertThat(content).contains("hello world");
    }

    @Test
    void writeBodyWithParent() throws Exception {
        result.addElement(new Element(0, "paragraph", "child content"));
        writer.writeBody(tempDir, "body.md", result, 2, "child.txt", "parent/doc");
        String content = Files.readString(tempDir.resolve("body.md"));
        assertThat(content).contains("PARENT: parent/doc");
    }

    @Test
    void writeBodyWithLargeTable() throws Exception {
        result.addLargeTable(new LargeTableInfo("Sheet1", 5, "col1 | col2",
                "Columns: col1 | col2\nRow 1: a, b", 10, List.of()));
        writer.writeBody(tempDir, "body.md", result, 1, "test.xlsx", null);
        String content = Files.readString(tempDir.resolve("body.md"));
        assertThat(content).contains("data_ref");
        assertThat(content).contains("Sheet1");
    }

    @Test
    void writeBodyWithImage() throws Exception {
        result.addImage(new ImageFile("img.png", 0, new byte[]{1, 2, 3}, "png"));
        writer.writeBody(tempDir, "body.md", result, 1, "test.docx", null);
        String content = Files.readString(tempDir.resolve("body.md"));
        assertThat(content).contains("media/img.png");
    }

    @Test
    void writeBodyWithMetadataContainingNewlines() throws Exception {
        result.addElement(new Element(0, "embed", null, "file: a\nb"));
        writer.writeBody(tempDir, "body.md", result, 1, "test.docx", null);
        String content = Files.readString(tempDir.resolve("body.md"));
        assertThat(content).doesNotContain("\n\n");
    }

    @Test
    void writeChunksNormal() throws Exception {
        for (int i = 0; i < 120; i++) {
            result.addElement(new Element(i, "paragraph", "p" + i));
        }
        writer.writeChunks(tempDir, "doc1", "body.md", result);
        assertThat(tempDir.resolve("chunks/index.json")).exists();
        assertThat(tempDir.resolve("chunks/chunk_0001.md")).exists();
        assertThat(tempDir.resolve("chunks/chunk_0003.md")).exists();
        assertThat(tempDir.resolve("chunks/chunk_0004.md")).doesNotExist();
    }

    @Test
    void writeChunksEmpty() throws Exception {
        writer.writeChunks(tempDir, "doc1", "body.md", result);
        assertThat(tempDir.resolve("chunks/chunk_0001.md")).exists();
    }

    @Test
    void writeChunksWithLargeTable() throws Exception {
        result.addElement(new Element(0, "paragraph", "p1"));
        result.addElement(new Element(1, "paragraph", "p2"));
        result.addLargeTable(new LargeTableInfo("big", 1, "c1",
                "Columns: c1", 100, List.of()));
        writer.writeChunks(tempDir, "doc1", "body.md", result);
        String indexJson = Files.readString(tempDir.resolve("chunks/index.json"));
        assertThat(indexJson).contains("includes_large_table");
    }

    @Test
    void writeSourceFileNormal() throws Exception {
        byte[] data = "source content".getBytes();
        writer.writeSourceFile(tempDir, data, "original.txt");
        assertThat(tempDir.resolve("original.txt")).exists();
        assertThat(Files.readAllBytes(tempDir.resolve("original.txt"))).isEqualTo(data);
    }

    @Test
    void writeSourceFileTooLarge() {
        byte[] huge = new byte[201 * 1024 * 1024];
        assertThatThrownBy(() -> writer.writeSourceFile(tempDir, huge, "huge.bin"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void writeMediaEmpty() throws Exception {
        writer.writeMedia(tempDir, result);
        assertThat(tempDir.resolve("media")).doesNotExist();
    }

    @Test
    void writeMediaWithImages() throws Exception {
        result.addImage(new ImageFile("test.png", 0, new byte[]{1, 2, 3}, "png"));
        writer.writeMedia(tempDir, result);
        assertThat(tempDir.resolve("media/test.png")).exists();
    }

    @Test
    void writeMediaSkipsExisting() throws Exception {
        Files.createDirectories(tempDir.resolve("media"));
        Files.write(tempDir.resolve("media/existing.png"), new byte[]{0});
        result.addImage(new ImageFile("existing.png", 0, new byte[]{1, 2, 3}, "png"));
        writer.writeMedia(tempDir, result);
        assertThat(Files.readAllBytes(tempDir.resolve("media/existing.png"))).isEqualTo(new byte[]{0});
    }

    @Test
    void writeLargeTablesEmpty() throws Exception {
        writer.writeLargeTables(tempDir, result);
        assertThat(tempDir.resolve("data")).doesNotExist();
    }

    @Test
    void writeLargeTablesWithData() throws Exception {
        List<List<String>> rows = List.of(
                List.of("col1", "col2"),
                List.of("a", "b")
        );
        result.addLargeTable(new LargeTableInfo("table1", 0, "col1 | col2",
                "Columns: col1 | col2", 2, rows));
        writer.writeLargeTables(tempDir, result);
        assertThat(tempDir.resolve("data/table1.csv")).exists();
        String csv = Files.readString(tempDir.resolve("data/table1.csv"));
        assertThat(csv).contains("col1,col2");
        assertThat(csv).contains("a,b");
    }

    @Test
    void writeLargeTablesWithCsvEscaping() throws Exception {
        List<List<String>> rows = List.of(
                List.of("name", "desc"),
                List.of("a,b", "c\"d")
        );
        result.addLargeTable(new LargeTableInfo("esc", 0, "name | desc",
                "Columns: name | desc", 2, rows));
        writer.writeLargeTables(tempDir, result);
        String csv = Files.readString(tempDir.resolve("data/esc.csv"));
        assertThat(csv).contains("\"a,b\"");
        assertThat(csv).contains("\"c\"\"d\"");
    }

    @Test
    void buildManifestJsonNormal() {
        var docs = List.of(
                new StoreWriter.DocInfo(1, "doc1", "test.docx", "test.docx", "docx",
                        10, 0, 1, 1, null)
        );
        String json = StoreWriter.buildManifestJson("sess-1", "source.docx", docs);
        assertThat(json).contains("sess-1");
        assertThat(json).contains("docx");
        assertThat(json).contains("\"status\"");
        assertThat(json).contains("done");
    }

    @Test
    void buildManifestJsonWithParent() {
        var docs = List.of(
                new StoreWriter.DocInfo(1, "doc1", "child.docx", "child.docx", "docx",
                        5, 0, 0, 1, "parent/pos=0")
        );
        String json = StoreWriter.buildManifestJson("s-2", "parent.docx", docs);
        assertThat(json).contains("parent/pos=0");
    }

    @Test
    void buildManifestJsonWithFiltered() {
        var docs = List.of(
                new StoreWriter.DocInfo(1, "doc1", "f.txt", "f.txt", "txt",
                        "root", "filtered")
        );
        docs.get(0).setFilterReason("too small");
        docs.get(0).setFilterConfidence(0.95);
        String json = StoreWriter.buildManifestJson("s-3", "parent.docx", docs);
        assertThat(json).contains("filtered");
        assertThat(json).contains("too small");
        assertThat(json).contains("0.95");
    }

    @Test
    void buildManifestJsonFilteredWithoutReason() {
        var docs = List.of(
                new StoreWriter.DocInfo(1, "doc1", "f.txt", "f.txt", "txt",
                        "root", "filtered")
        );
        String json = StoreWriter.buildManifestJson("s-4", "p.docx", docs);
        assertThat(json).doesNotContain("filter_reason");
    }

    @Test
    void buildManifestJsonSerializationFallback() {
        String json = StoreWriter.buildManifestJson(null, null, null);
        assertThat(json).isNotNull();
    }

    @Test
    void writeBodyWithEmptyResultWritesMinimalFile() throws Exception {
        writer.writeBody(tempDir, "body.md", result, 3, "source.txt", null);
        assertThat(tempDir.resolve("body.md")).exists();
    }

    @Test
    void writeChunksWithNoElementsStillWritesIndex() throws Exception {
        writer.writeChunks(tempDir, "doc1", "body.md", result);
        assertThat(tempDir.resolve("chunks/index.json")).exists();
    }
}
