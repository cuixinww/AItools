package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.DocxHandler;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExtractPipelineTest {

    private final ExtractPipeline pipeline = new ExtractPipeline(
            List.of(new DocxHandler()), 10, 200L * 1024 * 1024);

    @Test
    void shouldExtractSimpleDocx() throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Hello V2", "World");
        ExtractionResult result = pipeline.extract(TestFileFactory.toInputStream(docx), "hello.docx");
        assertThat(result).isNotNull();
        assertThat(result.getFileName()).isEqualTo("hello.docx");
    }

    @Test
    void shouldHandleEmptyFile() throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx();
        ExtractionResult result = pipeline.extract(TestFileFactory.toInputStream(docx), "empty.docx");
        assertThat(result).isNotNull();
    }

    @Test
    void shouldResolveDocxHandler() {
        DocxHandler handler = new DocxHandler();
        assertThat(handler.supports("test.docx")).isTrue();
        assertThat(handler.supports("test.docm")).isTrue();
    }
}
