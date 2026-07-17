package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class ZipHandlerTest {

    private final ZipHandler handler = new ZipHandler();

    @Test
    void shouldExtractEntries() throws Exception {
        byte[] zip = TestFileFactory.createSimpleZip("data1".getBytes(), "data2".getBytes());
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(zip), "test.zip");

        assertThat(result.getEmbeddedFiles()).hasSize(2);
    }

    @Test
    void shouldHandleEmptyZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) { }
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(baos.toByteArray()), "empty.zip");

        assertThat(result.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void shouldSupportZip() {
        assertThat(handler.supports("test.zip")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }
}
