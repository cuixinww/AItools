package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.handler.ZipHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

class ZipHandlerTest {

    private final ZipHandler handler = new ZipHandler();

    @Test
    void shouldSupportZip() {
        assertThat(handler.supports("test.zip")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }

    @Test
    void shouldExtractEntries() throws Exception {
        byte[] zip = TestFileFactory.createSimpleZip(
                "content1".getBytes(),
                "content2".getBytes()
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(zip), "test.zip");

        assertThat(result.getEmbeddedFiles()).hasSize(2);
        assertThat(result.getEmbeddedFiles().get(0).getFileName()).isEqualTo("file_0.bin");
    }

    @Test
    void shouldHandleEmptyZip() throws Exception {
        byte[] zip = TestFileFactory.createSimpleZip();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(zip), "empty.zip");
        assertThat(result.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void shouldUnpack() throws Exception {
        byte[] zip = TestFileFactory.createSimpleZip("data".getBytes());
        ExtractionResult unpacked = handler.unpack(TestFileFactory.toInputStream(zip), "test.zip");

        assertThat(unpacked.getEmbeddedFiles()).hasSize(1);
        assertThat(unpacked.getFileType()).isEqualTo("zip");
    }

    @Test
    void shouldHandleNullFileName() {
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "test.zip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectPathTraversal() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry evil = new java.util.zip.ZipEntry("../evil.txt");
            zos.putNextEntry(evil);
            zos.write("malicious".getBytes());
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("safe.txt"));
            zos.write("ok".getBytes());
            zos.closeEntry();
        }
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(baos.toByteArray()), "test.zip");
        assertThat(result.getEmbeddedFiles()).allMatch(ef -> !ef.getFileName().contains(".."));
    }

    @Test
    void shouldReportErrorWhenZipContainsNoEntries() throws Exception {
        byte[] zip = TestFileFactory.createSimpleZip();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(zip), "empty.zip");
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void shouldSkipInvalidEntry() throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("bad.bin"));
            zos.write(new byte[0]);
            zos.closeEntry();
        }
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(baos.toByteArray()), "test.zip");
        assertThat(result.getEmbeddedFiles()).isEmpty();
    }
}
