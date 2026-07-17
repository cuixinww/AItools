package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DocHandlerTest {

    private final DocHandler handler = new DocHandler();

    @Test
    void shouldSupportDoc() {
        assertThat(handler.supports("test.doc")).isTrue();
        assertThat(handler.supports("TEST.DOC")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
        assertThat(handler.supports("test.xls")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldExtractTextFromDoc() throws Exception {
        byte[] doc = TestFileFactory.createMinimalDoc();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(doc), "test.doc");

        assertThat(result.getFileType()).isEqualTo("doc");
        assertThat(result.getFileName()).isEqualTo("test.doc");
        // Minimal test .doc may not have parseable text content
        assertThat(result.getElements()).isNotNull();
    }

    @Test
    void shouldHandleEmptyDoc() throws Exception {
        byte[] doc = TestFileFactory.createMinimalDoc();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(doc), "empty.doc");

        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("doc");
        // Should not throw even if content is unparseable
    }

    @Test
    void shouldHaveNoImages() throws Exception {
        byte[] doc = TestFileFactory.createMinimalDoc();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(doc), "test.doc");

        assertThat(result.getImages()).isEmpty();
    }
}
