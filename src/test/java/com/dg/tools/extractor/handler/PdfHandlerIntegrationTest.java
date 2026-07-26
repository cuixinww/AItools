package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class PdfHandlerIntegrationTest {

    private final PdfHandler handler = new PdfHandler();

    @Test
    void extractRealPdf() throws Exception {
        Path pdfPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.pdf");
        assertThat(pdfPath).exists();
        try (InputStream is = Files.newInputStream(pdfPath)) {
            ExtractionResult result = handler.extract(is, pdfPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("pdf");
            assertThat(result.getElements()).isNotEmpty();
        }
    }

    @Test
    void unpackRealPdf() throws Exception {
        Path pdfPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.pdf");
        try (InputStream is = Files.newInputStream(pdfPath)) {
            ExtractionResult result = handler.unpack(is, pdfPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("pdf");
        }
    }

    @Test
    void supportsPdf() {
        assertThat(handler.supports("test.pdf")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void handleInvalidPdfGracefully() throws Exception {
        byte[] invalidData = "not a pdf".getBytes();
        ExtractionResult result = handler.extract(new java.io.ByteArrayInputStream(invalidData), "bad.pdf");
        assertThat(result.getErrors()).isNotEmpty();
    }
}
