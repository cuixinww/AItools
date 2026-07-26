package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.handler.DocHandler;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class DocHandlerIntegrationTest {

    private final DocHandler handler = new DocHandler();

    @Test
    void extractRealDoc() throws Exception {
        Path docPath = Path.of("doc/保险项目用户需求说明书.doc");
        assertThat(docPath).exists();
        try (InputStream is = Files.newInputStream(docPath)) {
            ExtractionResult result = handler.extract(is, docPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("doc");
            assertThat(result.getElements()).isNotEmpty();
        }
    }

    @Test
    void supportsDoc() {
        assertThat(handler.supports("test.doc")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void handleInvalidDocGracefully() throws Exception {
        byte[] invalidData = "not a valid doc".getBytes();
        ExtractionResult result = handler.extract(new java.io.ByteArrayInputStream(invalidData), "bad.doc");
        assertThat(result.getErrors()).isNotEmpty();
    }
}
