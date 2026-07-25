package com.dg.tools.extractor;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.handler.PdfHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PdfHandlerTest {

    private final PdfHandler handler = new PdfHandler();

    @Test
    void shouldSupportPdf() {
        assertThat(handler.supports("test.pdf")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }

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

    @Test
    void shouldExtractImagesFromPdf() throws Exception {
        byte[] pdf = TestFileFactory.createPdfWithImage();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "img.pdf");

        assertThat(result.getImages()).isNotEmpty();
    }

    @Test
    void shouldHaveNoImagesInTextOnlyPdf() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("Just text");
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(pdf), "text.pdf");

        assertThat(result.getImages()).isEmpty();
    }

    @Test
    void shouldHandleNullFileName() {
        assertThat(handler.supports(null)).isFalse();
    }
}
