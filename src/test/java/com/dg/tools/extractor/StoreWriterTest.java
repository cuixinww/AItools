package com.dg.tools.extractor;

import com.dg.tools.extractor.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StoreWriterTest {

    private final StoreWriter writer = new StoreWriter();

    private ExtractionResult sampleResult() {
        ExtractionResult r = new ExtractionResult("docx", "doc.docx");
        r.addElement(new Element(0, "paragraph", "第一段"));
        r.addElement(new Element(1, "paragraph", "第二段"));
        r.addElement(new Element(2, "table", "| A | B |\n| --- | --- |\n| 1 | 2 |"));
        r.addImage(new ImageFile("img.png", 3, new byte[]{1, 2, 3}, "png"));
        r.addLargeTable(new LargeTableInfo("表1", 4, "列A | 列B",
                "Columns: 列A | 列B\nRow 1: a, b", 5, List.of(
                        List.of("列A", "列B"),
                        List.of("a", "b"),
                        List.of("c", "d"),
                        List.of("e", "f"),
                        List.of("g", "h")
                )));
        return r;
    }

    @Test
    void shouldWriteBodyMd(@TempDir Path dir) throws Exception {
        Path docDir = dir.resolve("doc");
        writer.writeBody(docDir, "doc.md", sampleResult(), 0, "doc.docx", "root");

        Path body = docDir.resolve("doc.md");
        assertThat(body).exists();
        String content = Files.readString(body);
        assertThat(content).contains("# DOC: 0");
        assertThat(content).contains("# SOURCE: doc.docx");
        assertThat(content).contains("第一段");
        assertThat(content).contains("TYPE: image");
        assertThat(content).contains("TYPE: data_ref");
    }

    @Test
    void shouldWriteBodyMdWithParentInfo(@TempDir Path dir) throws Exception {
        Path docDir = dir.resolve("child");
        ExtractionResult r = new ExtractionResult("xlsx", "c.xlsx");
        r.addElement(new Element(0, "sheet_header", "[Sheet: S]"));
        writer.writeBody(docDir, "child.md", r, 1, "c.xlsx", "parent, pos=5");

        String content = Files.readString(docDir.resolve("child.md"));
        assertThat(content).contains("# PARENT: parent, pos=5");
    }

    @Test
    void shouldWriteChunks(@TempDir Path dir) throws Exception {
        ExtractionResult r = new ExtractionResult("docx", "d.docx");
        for (int i = 0; i < 60; i++) {
            r.addElement(new Element(i, "paragraph", "段落 " + i));
        }
        Path docDir = dir.resolve("d");
        writer.writeChunks(docDir, "d", "d.md", r);

        Path chunks = docDir.resolve("chunks");
        assertThat(chunks.resolve("index.json")).exists();
        assertThat(chunks.resolve("chunk_0001.md")).exists();
        assertThat(chunks.resolve("chunk_0002.md")).exists();

        String idx = Files.readString(chunks.resolve("index.json"));
        assertThat(idx).contains("\"total_elements\" : 60");
        assertThat(idx).contains("\"chunk_size\" : 50");
    }

    @Test
    void shouldWriteSingleChunkForSmallDoc(@TempDir Path dir) throws Exception {
        ExtractionResult r = new ExtractionResult("docx", "s.docx");
        r.addElement(new Element(0, "paragraph", "x"));
        Path docDir = dir.resolve("s");
        writer.writeChunks(docDir, "s", "s.md", r);

        Path chunks = docDir.resolve("chunks");
        assertThat(chunks.resolve("chunk_0001.md")).exists();
        assertThat(chunks.resolve("chunk_0002.md")).doesNotExist();
    }

    @Test
    void shouldWriteSourceFile(@TempDir Path dir) throws Exception {
        byte[] data = new byte[]{9, 8, 7};
        writer.writeSourceFile(dir.resolve("doc"), data, "doc.docx");
        assertThat(dir.resolve("doc").resolve("doc.docx")).hasBinaryContent(data);
    }

    @Test
    void shouldThrowWhenSourceFileTooLarge(@TempDir Path dir) {
        byte[] big = new byte[201 * 1024 * 1024];
        assertThatThrownBy(() -> writer.writeSourceFile(dir.resolve("doc"), big, "big.docx"))
                .isInstanceOf(Exception.class);
    }

    @Test
    void shouldWriteMedia(@TempDir Path dir) throws Exception {
        ExtractionResult r = sampleResult();
        writer.writeMedia(dir.resolve("doc"), r);
        assertThat(dir.resolve("doc").resolve("media").resolve("img.png")).exists();
    }

    @Test
    void shouldNotOverwriteExistingMedia(@TempDir Path dir) throws Exception {
        Path docDir = dir.resolve("doc");
        Path media = docDir.resolve("media");
        Files.createDirectories(media);
        Path existing = media.resolve("img.png");
        Files.write(existing, new byte[]{42});
        // 阶段 1 已写入，阶段 2 不应覆盖
        writer.writeMedia(docDir, sampleResult());
        assertThat(existing).hasBinaryContent(new byte[]{42});
    }

    @Test
    void shouldWriteLargeTablesAsCsv(@TempDir Path dir) throws Exception {
        Path docDir = dir.resolve("doc");
        writer.writeLargeTables(docDir, sampleResult());

        Path csv = docDir.resolve("data").resolve("表1.csv");
        assertThat(csv).exists();
        long lines = Files.lines(csv).count();
        assertThat(lines).isEqualTo(5);
    }

    @Test
    void shouldBuildManifestJson() {
        StoreWriter.DocInfo d1 = new StoreWriter.DocInfo(0, "doc", "doc.docx",
                "doc/doc.docx", "docx", 3, 1, 1, "root");
        StoreWriter.DocInfo d2 = new StoreWriter.DocInfo(1, "sub", "sub.xlsx",
                "sub/sub.xlsx", "xlsx", "parent, pos=2", "filtered");
        d2.filterReason = "无关文档";
        d2.filterConfidence = 0.9;

        String json = StoreWriter.buildManifestJson("sess1", "doc.docx", List.of(d1, d2));

        assertThat(json).contains("\"session_id\" : \"sess1\"");
        assertThat(json).contains("\"source_file\" : \"doc.docx\"");
        assertThat(json).contains("\"seq\" : 0");
        assertThat(json).contains("\"element_count\" : 3");
        assertThat(json).contains("\"data_ref_count\" : 1");
        assertThat(json).contains("\"image_count\" : 1");
        assertThat(json).contains("\"status\" : \"filtered\"");
        assertThat(json).contains("\"filter_reason\" : \"无关文档\"");
        assertThat(json).contains("\"filter_confidence\" : 0.9");
    }

    @Test
    void shouldBuildManifestJsonWithUnknownSource() {
        String json = StoreWriter.buildManifestJson("s", "x.docx", List.of());
        assertThat(json).contains("\"source_file\" : \"x.docx\"");
        assertThat(json).contains("\"docs\" : [ ]");
    }
}
