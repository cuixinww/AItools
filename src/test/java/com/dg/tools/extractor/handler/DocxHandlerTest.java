package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.EmbeddedFile;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class DocxHandlerTest {

    private final DocxHandler handler = new DocxHandler();

    // ========== Basic extraction ==========

    @Test
    void shouldExtractParagraphs() throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("第一段", "第二段");
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "test.docx");

        assertThat(result.getElements()).hasSize(2);
        assertThat(result.getElements().get(0).getContent()).isEqualTo("第一段");
        assertThat(result.getElements().get(0).getType()).isEqualTo("paragraph");
    }

    @Test
    void shouldExtractTables() throws Exception {
        byte[] docx = TestFileFactory.createDocxWithTables(
                new String[]{"表前文字"},
                new String[][]{{"姓名", "年龄"}, {"张三", "28"}}
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "test.docx");

        List<Element> tables = result.getElements().stream()
                .filter(e -> "table".equals(e.getType())).toList();
        assertThat(tables).hasSize(1);
        assertThat(tables.get(0).getContent()).contains("姓名").contains("张三");
    }

    @Test
    void shouldHandleEmptyDocx() throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "empty.docx");
        assertThat(result.getElements()).isEmpty();
    }

    @Test
    void shouldDetectEmbeddedFiles() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("S1", new String[]{"A"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("data.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx)
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "parent.docx");

        assertThat(result.getEmbeddedFiles()).hasSize(1);
        assertThat(result.getEmbeddedFiles().get(0).getFileName()).isEqualTo("data.xlsx");
    }

    @Test
    void shouldDetectMultipleEmbeddedFiles() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("S1", new String[]{"A"});
        byte[] pdf = TestFileFactory.createSimplePdf("PDF text");
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("a.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx),
                new TestFileFactory.EmbeddedEntry("b.pdf", "application/pdf", pdf)
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "p.docx");

        assertThat(result.getEmbeddedFiles()).hasSize(2);
    }

    // ========== New: image extraction ==========

    @Test
    void shouldExtractImagesFromDocx() throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "with_image.docx");

        assertThat(result.getImages()).isNotEmpty();
        assertThat(result.getImages().get(0).getFileName()).contains("png");
        assertThat(result.getImages().get(0).getData()).isNotEmpty();
    }

    @Test
    void shouldNotDuplicateImages() throws Exception {
        byte[] docx = TestFileFactory.createDocxWithImage();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "img.docx");

        // Image should appear once, not per-reference
        assertThat(result.getImages()).hasSize(1);
    }

    // ========== New: OLE extraction ==========

    @Test
    void shouldExtractOleEmbeddedFiles() throws Exception {
        byte[] docx = TestFileFactory.createDocxWithOleEmbedding();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "ole.docx");

        // OLE embedded .docx should appear as an embedded file
        assertThat(result.getEmbeddedFiles()).isNotEmpty();
    }

    // ========== New: .docm support ==========

    @Test
    void shouldSupportDocm() {
        assertThat(handler.supports("test.docm")).isTrue();
    }

    // ========== Legacy tests ==========

    @Test
    void shouldSupportDocx() {
        assertThat(handler.supports("test.docx")).isTrue();
        assertThat(handler.supports("test.xlsx")).isFalse();
    }
}
