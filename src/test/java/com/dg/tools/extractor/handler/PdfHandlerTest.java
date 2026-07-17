package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PdfHandlerTest {

    private final PdfHandler handler = new PdfHandler();

    // ========== Basic text extraction ==========

    @Test
    void shouldExtractText() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("Hello PDF", "Line 2");
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "test.pdf");

        assertThat(result.getElements()).isNotEmpty();
        String text = result.getElements().stream()
                .map(Element::getContent)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(text).contains("Hello PDF").contains("Line 2");
    }

    @Test
    void shouldHandleEmptyPdf() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "empty.pdf");
        assertThat(result.getElements()).isEmpty();
    }

    // ========== New: image extraction ==========

    @Test
    void shouldExtractImagesFromPdf() throws Exception {
        byte[] pdf = TestFileFactory.createPdfWithImage();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "img.pdf");

        // PDF with embedded image should extract the image
        assertThat(result.getImages()).isNotEmpty();
    }

    @Test
    void shouldHaveNoImagesInTextOnlyPdf() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("Just text");
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "text.pdf");

        assertThat(result.getImages()).isEmpty();
    }

    // ========== Support ==========

    @Test
    void shouldSupportPdf() {
        assertThat(handler.supports("test.pdf")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }
}
