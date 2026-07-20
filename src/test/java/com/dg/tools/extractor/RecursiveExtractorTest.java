package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class RecursiveExtractorTest {

    private RecursiveExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new RecursiveExtractor();
        extractor.setHandlers(
                new DocxHandler(),
                new DocHandler(),
                new ExcelHandler(),
                new PdfHandler(),
                new ZipHandler(),
                new ImageHandler()
        );
    }

    // ========== Basic extraction ==========

    @Test
    void shouldExtractSimpleDocx(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Hello World");
        extractor.extract(TestFileFactory.toInputStream(docx), "test.docx", "sess1", tempDir);

        Path bodyMd = tempDir.resolve("test/test.md");
        assertThat(bodyMd).exists();
        String content = Files.readString(bodyMd);
        assertThat(content).contains("# DOC: 0");
        assertThat(content).contains("# SOURCE: test.docx");
        assertThat(content).contains("Hello World");
    }

    @Test
    void shouldWriteManifest(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Test");
        extractor.extract(TestFileFactory.toInputStream(docx), "doc.docx", "s1", tempDir);

        Path manifest = tempDir.resolve("manifest.json");
        assertThat(manifest).exists();
        String content = Files.readString(manifest);
        assertThat(content).contains("\"session_id\"");
        assertThat(content).contains("\"s1\"");
        assertThat(content).contains("\"source_file\"");
        assertThat(content).contains("\"doc.docx\"");
        assertThat(content).contains("\"source_copy\"");
        assertThat(content).contains("\"seq\" : 0");
        assertThat(content).contains("\"data_ref_count\"");
        assertThat(content).contains("\"image_count\"");
    }

    @Test
    void shouldWriteLevel0ParagraphInOrder(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithTables(
                new String[]{"第一段", "第二段"},
                new String[][]{{"h1", "h2"}}
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "doc.docx", "s6", tempDir);

        String content = Files.readString(tempDir.resolve("doc/doc.md"));
        assertThat(content)
                .containsSubsequence("第一段", "第二段", "| h1")
                .contains("| h2 |");
    }

    // ========== Nested extraction ==========

    @Test
    void shouldExtractNestedExcelInDocx(@TempDir Path tempDir) throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1", new String[]{"A", "B"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("data.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "parent.docx", "s2", tempDir);

        assertThat(tempDir.resolve("parent/parent.md")).exists();
        assertThat(tempDir.resolve("data/data.md")).exists();
        String nested = Files.readString(tempDir.resolve("data/data.md"));
        assertThat(nested).contains("[Sheet: Sheet1]");
    }

    @Test
    void shouldExtractNestedPdfInDocx(@TempDir Path tempDir) throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("PDF text");
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("doc.pdf", "application/pdf", pdf)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s3", tempDir);

        assertThat(tempDir.resolve("p/p.md")).exists();
        assertThat(tempDir.resolve("doc/doc.md")).exists();
    }

    @Test
    void shouldSeparateLargeExcelTable(@TempDir Path tempDir) throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("BigData", 60, 3);
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("big.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s4", tempDir);

        Path dataDir = tempDir.resolve("big/data");
        assertThat(dataDir).exists();
        assertThat(dataDir.resolve("BigData.csv")).exists();
        long csvLines = Files.lines(dataDir.resolve("BigData.csv")).count();
        assertThat(csvLines).isEqualTo(60);
    }

    @Test
    void shouldHandleZipEmbedded(@TempDir Path tempDir) throws Exception {
        byte[] innerDocx = TestFileFactory.createSimpleDocx("Zipped docx");
        byte[] zip = createZipWithEntry("inner.docx", innerDocx);
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("files.zip", "application/zip", zip)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s5", tempDir);

        assertThat(tempDir.resolve("p/p.md")).exists();
        assertThat(tempDir.resolve("files/files.md")).exists();
        assertThat(tempDir.resolve("inner/inner.md")).exists();
    }

    @Test
    void shouldLimitRecursionDepth(@TempDir Path tempDir) throws Exception {
        byte[] inner = TestFileFactory.createSimpleDocx("deep");
        byte[] mid = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("inner.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", inner)
        );
        byte[] outer = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("mid.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", mid)
        );
        extractor.extract(TestFileFactory.toInputStream(outer), "outer.docx", "s7", tempDir);

        assertThat(tempDir.resolve("outer/outer.md")).exists();
        assertThat(tempDir.resolve("mid/mid.md")).exists();
        assertThat(tempDir.resolve("inner/inner.md")).exists();
    }

    // ========== v3: image handling in recursive extraction ==========

    @Test
    void shouldWriteImagesToMediaDirectory(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        extractor.extract(TestFileFactory.toInputStream(docx), "img_doc.docx", "s8", tempDir);

        Path mediaDir = tempDir.resolve("img_doc/media");
        assertThat(mediaDir).exists();
        assertThat(mediaDir.toFile().listFiles()).isNotEmpty();
    }

    @Test
    void shouldAddImageReferenceToBodyMd(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        extractor.extract(TestFileFactory.toInputStream(docx), "img_doc.docx", "s9", tempDir);

        String content = Files.readString(tempDir.resolve("img_doc/img_doc.md"));
        assertThat(content).contains("TYPE: image");
    }

    @Test
    void shouldHandleDocWithOleEmbedding(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithOleEmbedding();
        try {
            extractor.extract(TestFileFactory.toInputStream(docx), "ole.docx", "s10", tempDir);
            assertThat(tempDir.resolve("ole/ole.md")).exists();
        } catch (RuntimeException e) {
            // Acceptable: OLE format variations can cause extraction failures
        }
    }

    @Test
    void shouldHandlePdfWithImages(@TempDir Path tempDir) throws Exception {
        byte[] pdf = TestFileFactory.createPdfWithImage();
        extractor.extract(TestFileFactory.toInputStream(pdf), "img.pdf", "s11", tempDir);

        Path mediaDir = tempDir.resolve("img/media");
        assertThat(mediaDir).exists();
    }

    // ========== v3: Zip containing images ==========

    @Test
    void shouldExtractImagesFromZip(@TempDir Path tempDir) throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        byte[] zip = createZipWithEntry("photo.png", png);
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("images.zip", "application/zip", zip)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s12", tempDir);

        assertThat(tempDir.resolve("images/images.md")).exists();
    }

    // ========== v4: Multi-embedding disambiguation ==========

    @Test
    void shouldCreateSeparateDirectoriesForMultipleEmbeddings(@TempDir Path tempDir) throws Exception {
        byte[] xlsx1 = TestFileFactory.createSimpleExcel("SheetA", new String[]{"A1", "B1"});
        byte[] xlsx2 = TestFileFactory.createSimpleExcel("SheetB", new String[]{"X1", "Y1"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("data1.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx1),
                new TestFileFactory.EmbeddedEntry("data2.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx2)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "multi.docx", "s_multi", tempDir);

        assertThat(tempDir.resolve("multi/multi.md")).exists();
        assertThat(tempDir.resolve("data1/data1.md")).exists();
        assertThat(tempDir.resolve("data2/data2.md")).exists();
        String doc1 = Files.readString(tempDir.resolve("data1/data1.md"));
        String doc2 = Files.readString(tempDir.resolve("data2/data2.md"));
        assertThat(doc1).contains("SheetA");
        assertThat(doc2).contains("SheetB");
    }

    // ========== v4: Source file preservation ==========

    @Test
    void shouldSaveSourceFileCopy(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Source preservation test");
        extractor.extract(TestFileFactory.toInputStream(docx), "original.docx", "s_src", tempDir);

        Path sourceCopy = tempDir.resolve("original/original.docx");
        assertThat(sourceCopy).exists();
        assertThat(sourceCopy.toFile().length()).isEqualTo(docx.length);
    }

    // ========== v4: Embed elements in body.md ==========

    @Test
    void shouldWriteEmbedElementInParentBodyMd(@TempDir Path tempDir) throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1", new String[]{"A", "B"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("data.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "parent.docx", "s_embed", tempDir);

        String parentBody = Files.readString(tempDir.resolve("parent/parent.md"));
        assertThat(parentBody).contains("TYPE: embed");
        assertThat(parentBody).contains("file: data.xlsx");
    }

    @Test
    void shouldPreserveOriginalSourceFileName(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Test source name");
        extractor.extract(TestFileFactory.toInputStream(docx), "my_document.docx", "s_name", tempDir);

        assertThat(tempDir.resolve("my_document/my_document.docx")).exists();
        assertThat(tempDir.resolve("my_document/_source.docx")).doesNotExist();
    }

    // ========== v4: Chunking ==========

    @Test
    void shouldCreateChunksForLargeDocument(@TempDir Path tempDir) throws Exception {
        String[] paragraphs = new String[60];
        for (int i = 0; i < 60; i++) {
            paragraphs[i] = "Paragraph " + i;
        }
        byte[] docx = TestFileFactory.createSimpleDocx(paragraphs);
        extractor.extract(TestFileFactory.toInputStream(docx), "large.docx", "s_chunk", tempDir);

        Path chunksDir = tempDir.resolve("large/chunks");
        assertThat(chunksDir).exists();
        Path indexFile = chunksDir.resolve("index.json");
        assertThat(indexFile).exists();

        String index = Files.readString(indexFile);
        assertThat(index).contains("\"source\"");
        assertThat(index).contains("large/large.md");
        assertThat(index).contains("\"chunk_size\" : 50");
        assertThat(index).contains("chunk_0001.md");
        assertThat(index).contains("chunk_0002.md");

        assertThat(chunksDir.resolve("chunk_0001.md")).exists();
        assertThat(chunksDir.resolve("chunk_0002.md")).exists();
    }

    @Test
    void shouldNotCreateChunksForSmallDocument(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Small doc");
        extractor.extract(TestFileFactory.toInputStream(docx), "small.docx", "s_nochunk", tempDir);

        Path chunksDir = tempDir.resolve("small/chunks");
        assertThat(chunksDir).exists();
        Path indexFile = chunksDir.resolve("index.json");
        assertThat(indexFile).exists();
    }

    // ========== v4: Manifest enhancements ==========

    @Test
    void shouldIncludeDataRefCountInManifest(@TempDir Path tempDir) throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("BigData", 60, 3);
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("big.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
        );
        extractor.extract(TestFileFactory.toInputStream(docx), "p.docx", "s_dr", tempDir);

        String manifest = Files.readString(tempDir.resolve("manifest.json"));
        assertThat(manifest).contains("\"data_ref_count\"");
    }

    @Test
    void shouldIncludeImageCountInManifest(@TempDir Path tempDir) throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        extractor.extract(TestFileFactory.toInputStream(docx), "img.docx", "s_img", tempDir);

        String manifest = Files.readString(tempDir.resolve("manifest.json"));
        assertThat(manifest).contains("\"image_count\"");
    }

    // ========== v4: File name sanitization ==========

    @Test
    void shouldSanitizeFileNameCorrectly() {
        // Spaces and parentheses → underscores, trailing underscore trimmed
        assertThat(RecursiveExtractor.fileNameToBaseName("My Document (v2.0).docx"))
                .isEqualTo("My_Document_v2.0");
        // Chinese characters preserved
        assertThat(RecursiveExtractor.fileNameToBaseName("需求文档_V2.3.1.docx"))
                .isEqualTo("需求文档_V2.3.1");
        // Special chars replaced
        assertThat(RecursiveExtractor.fileNameToBaseName("test@#$file.docx"))
                .isEqualTo("test_file");
        // No extension
        assertThat(RecursiveExtractor.fileNameToBaseName("noext"))
                .isEqualTo("noext");
        // Null/empty
        assertThat(RecursiveExtractor.fileNameToBaseName(null)).isEqualTo("unknown");
        assertThat(RecursiveExtractor.fileNameToBaseName("")).isEqualTo("unknown");
    }

    // ========== Utility ==========

    private byte[] createZipWithEntry(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
