package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class DocxHandlerIntegrationTest {

    private final DocxHandler handler = new DocxHandler();

    @Test
    void extractRealDocx() throws Exception {
        Path docxPath = Path.of("doc/SCM-NextGen_软件需求规格说明书_V2.3.1.docx");
        assertThat(docxPath).exists();
        try (InputStream is = Files.newInputStream(docxPath)) {
            ExtractionResult result = handler.extract(is, docxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("docx");
            assertThat(result.getElements()).isNotEmpty();
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Test
    void extractAnotherDocx() throws Exception {
        Path docxPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.docx");
        assertThat(docxPath).exists();
        try (InputStream is = Files.newInputStream(docxPath)) {
            ExtractionResult result = handler.extract(is, docxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("docx");
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Test
    void extractDocxWithChineseName() throws Exception {
        Path docxPath = Path.of("doc/保险项目用户需求说明书.docx");
        assertThat(docxPath).exists();
        try (InputStream is = Files.newInputStream(docxPath)) {
            ExtractionResult result = handler.extract(is, docxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("docx");
            assertThat(result.getErrors()).isEmpty();
        }
    }

    @Test
    void unpackRealDocx() throws Exception {
        Path docxPath = Path.of("doc/SCM-NextGen_软件需求规格说明书_V2.3.1.docx");
        try (InputStream is = Files.newInputStream(docxPath)) {
            ExtractionResult result = handler.unpack(is, docxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("docx");
        }
    }
}
