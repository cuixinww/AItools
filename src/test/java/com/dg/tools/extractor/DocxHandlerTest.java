package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.DocxHandler;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

class DocxHandlerTest {

    private final DocxHandler handler = new DocxHandler();

    @Test
    void shouldSupportDocx() {
        assertThat(handler.supports("test.docx")).isTrue();
        assertThat(handler.supports("test.docm")).isTrue();
        assertThat(handler.supports("test.doc")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "test.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "test.docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleInvalidDocxGracefully() throws Exception {
        byte[] invalidData = "not a valid docx file".getBytes();
        ExtractionResult result = handler.extract(new ByteArrayInputStream(invalidData), "bad.docx");
        assertThat(result).isNotNull();
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void shouldParseSimpleDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createSimpleDocx("Hello", "World");
        ExtractionResult result = handler.extract(new ByteArrayInputStream(docxBytes), "test.docx");
        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("docx");
    }

    @Test
    void shouldUnpackSimpleDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createSimpleDocx("Hello");
        ExtractionResult result = handler.unpack(new ByteArrayInputStream(docxBytes), "test.docx");
        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("docx");
    }
}
