package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExtractPipelineExtraTest {

    @Test
    void emptyHandlerList() {
        ExtractPipeline pipeline = new ExtractPipeline();
        ExtractionResult r = pipeline.extract(
                new ByteArrayInputStream(new byte[0]), "test.docx");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("no handler");
    }

    @Test
    void emptyHandlerListForUnpack() {
        ExtractPipeline pipeline = new ExtractPipeline();
        ExtractionResult r = pipeline.unpack(
                new ByteArrayInputStream(new byte[0]), "test.docx");
        assertThat(r.getErrors()).isNotEmpty();
    }

    @Test
    void unsupportedFileType() {
        ExtractPipeline pipeline = new ExtractPipeline(List.of(new DocxHandler()), 10, 200L * 1024 * 1024);
        ExtractionResult r = pipeline.extract(
                new ByteArrayInputStream(new byte[0]), "test.xyz");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("no handler");
    }

    @Test
    void extractWithNullFileName() {
        ExtractPipeline pipeline = new ExtractPipeline(List.of(new DocxHandler()), 10, 200L * 1024 * 1024);
        ExtractionResult r = pipeline.extract(
                new ByteArrayInputStream(new byte[0]), null);
        assertThat(r.getErrors()).isNotEmpty();
    }

    @Test
    void unpackWithZipHandler() throws Exception {
        ExtractPipeline pipeline = new ExtractPipeline(List.of(new ZipHandler()), 10, 200L * 1024 * 1024);
        byte[] zip = TestFileFactory.createSimpleZip("content".getBytes());
        ExtractionResult r = pipeline.unpack(
                new ByteArrayInputStream(zip), "test.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(1);
    }

    @Test
    void extractCatchesException() {
        ExtractPipeline pipeline = new ExtractPipeline(List.of(new ZipHandler()), 10, 200L * 1024 * 1024);
        ExtractionResult r = pipeline.extract(
                new ByteArrayInputStream("not zip".getBytes()), "test.zip");
        assertThat(r.getErrors()).isNotEmpty();
    }
}
