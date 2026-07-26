package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.handler.DocHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

class DocHandlerTest {

    private final DocHandler handler = new DocHandler();

    @Test
    void shouldSupportDoc() {
        assertThat(handler.supports("test.doc")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }

    @Test
    void shouldHandleNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "test.doc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleInvalidDocGracefully() {
        byte[] invalidData = "not a valid doc file".getBytes();
        ExtractionResult result = handler.extract(new ByteArrayInputStream(invalidData), "bad.doc");
        assertThat(result).isNotNull();
    }

    @Test
    void shouldHandleNullFileName() {
        assertThat(handler.supports(null)).isFalse();
    }
}
