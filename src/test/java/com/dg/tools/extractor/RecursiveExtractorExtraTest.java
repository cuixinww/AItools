package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class RecursiveExtractorExtraTest {

    private RecursiveExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new RecursiveExtractor();
        extractor.setHandlers(
                new DocxHandler(), new DocHandler(), new ExcelHandler(),
                new PdfHandler(), new ZipHandler(), new ImageHandler());
    }

    @Test
    void shouldMarkStatusDoneInManifest(@TempDir Path dir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("正文");
        extractor.extract(TestFileFactory.toInputStream(docx), "root.docx", "s", dir);

        String manifest = Files.readString(dir.resolve("manifest.json"));
        assertThat(manifest).contains("\"status\" : \"done\"");
        assertThat(manifest).contains("\"element_count\" : 1");
    }

    @Test
    void shouldKeepSourceCopy(@TempDir Path dir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("x");
        extractor.extract(TestFileFactory.toInputStream(docx), "root.docx", "s", dir);
        assertThat(dir.resolve("root").resolve("root.docx")).exists();
    }

    @Test
    void shouldSkipUnsupportedFileType(@TempDir Path dir) throws Exception {
        // 一个 .xyz 文件，没有任何 handler 支持 → 不生成目录
        byte[] data = "nothing".getBytes();
        extractor.extract(TestFileFactory.toInputStream(data), "weird.xyz", "s", dir);
        assertThat(dir.resolve("weird")).doesNotExist();
        assertThat(dir.resolve("manifest.json")).exists();
    }

    @Test
    void shouldHandleZipOfZipRecursion(@TempDir Path dir) throws Exception {
        // 内层 zip 再嵌套一个 docx
        byte[] innerDocx = TestFileFactory.createSimpleDocx("deep doc");
        byte[] innerZip = zipOf("inner.docx", innerDocx);
        byte[] outerZip = zipOf("outer.zip", innerZip);

        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("outer.zip", "application/zip", outerZip));
        extractor.extract(TestFileFactory.toInputStream(docx), "top.docx", "s", dir);

        // 三层都应被展开
        assertThat(dir.resolve("top").resolve("top.md")).exists();
        assertThat(dir.resolve("outer").resolve("outer.zip")).exists();
        assertThat(dir.resolve("inner").resolve("inner.md")).exists();
    }

    @Test
    void shouldWriteMediaForImagesInDocx(@TempDir Path dir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        extractor.extract(TestFileFactory.toInputStream(docx), "img.docx", "s", dir);
        assertThat(dir.resolve("img").resolve("media")).exists();
        assertThat(dir.resolve("img").resolve("img.md")).exists();
    }

    @Test
    void shouldReportImageCountInManifest(@TempDir Path dir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        extractor.extract(TestFileFactory.toInputStream(docx), "img.docx", "s", dir);
        String manifest = Files.readString(dir.resolve("manifest.json"));
        assertThat(manifest).contains("\"image_count\" : 1");
    }

    @Test
    void shouldExtractMultipleDistinctEmbeddedFiles(@TempDir Path dir) throws Exception {
        // 两个不同名的内嵌文件（docx 与 xlsx），验证各自生成独立目录
        byte[] a = TestFileFactory.createSimpleDocx("A");
        byte[] b = TestFileFactory.createSimpleExcel("S1", new String[]{"X"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("report.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", a),
                new TestFileFactory.EmbeddedEntry("data.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", b));
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s", dir);

        // 两个不同文档各自生成独立目录
        assertThat(dir.resolve("report").resolve("report.md")).exists();
        assertThat(dir.resolve("data").resolve("data.md")).exists();
    }

    @Test
    void shouldHandleDocWithOle(@TempDir Path dir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithOleEmbedding();
        // OLE 解析可能因格式差异失败，但不应抛未捕获异常（异常在 Session 内被吞掉标记 error）
        extractor.extract(TestFileFactory.toInputStream(docx), "ole.docx", "s", dir);
        assertThat(dir.resolve("manifest.json")).exists();
    }

    private byte[] zipOf(String name, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
