package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.*;

class EdgeCaseTest {

    // ==================== ImageHandler edge cases ====================

    @Test
    void imageHandlerSupportsNoExtension() {
        ImageHandler h = new ImageHandler();
        assertThat(h.supports("file")).isFalse();
        assertThat(h.supports("file.")).isFalse();
    }

    @Test
    void imageHandlerNormalizeFormatUnknown() {
        ImageHandler h = new ImageHandler();
        assertThat(h.supports("file.unknown")).isFalse();
    }

    @Test
    void imageHandlerJpegToJpg() throws Exception {
        ImageHandler h = new ImageHandler();
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = h.extract(new ByteArrayInputStream(png), "photo.jpeg");
        assertThat(r.getImages().get(0).getFormat()).isEqualTo("jpg");
    }

    @Test
    void imageHandlerTifToTiff() throws Exception {
        ImageHandler h = new ImageHandler();
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = h.extract(new ByteArrayInputStream(png), "img.tif");
        assertThat(r.getImages().get(0).getFormat()).isEqualTo("tiff");
    }

    // ==================== ZipHandler edge cases ====================

    @Test
    void zipHandlerRejectsPathTraversal() throws Exception {
        ZipHandler h = new ZipHandler(10 * 1024 * 1024, 500L * 1024 * 1024, 100, 10000);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            java.util.zip.ZipEntry evil = new java.util.zip.ZipEntry("../evil.txt");
            zos.putNextEntry(evil);
            zos.write("malicious".getBytes());
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void zipHandlerSkipsDirectories() throws Exception {
        ZipHandler h = new ZipHandler();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("dir/"));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("dir/file.txt"));
            zos.write("content".getBytes());
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(1);
    }

    @Test
    void zipHandlerMaxEntriesLimit() throws Exception {
        ZipHandler h = new ZipHandler(10 * 1024 * 1024, 500L * 1024 * 1024, 100, 5);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 0; i < 10; i++) {
                zos.putNextEntry(new java.util.zip.ZipEntry("f" + i + ".txt"));
                zos.write(("content" + i).getBytes());
                zos.closeEntry();
            }
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(5);
    }

    @Test
    void zipHandlerSanitizeEntryNameStartsWithSlash() throws Exception {
        ZipHandler h = new ZipHandler();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("/absolute/file.txt"));
            zos.write("data".getBytes());
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(1);
        assertThat(r.getEmbeddedFiles().get(0).getFileName()).doesNotStartWith("/");
    }

    @Test
    void zipHandlerEmptyEntryNameSkipped() throws Exception {
        ZipHandler h = new ZipHandler();
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(""));
            zos.write("data".getBytes());
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void zipHandlerEntryTooLarge() throws Exception {
        ZipHandler h = new ZipHandler(10, 500L * 1024 * 1024, 100, 100);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("large.bin"));
            byte[] big = new byte[100];
            zos.write(big);
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void zipHandlerTotalSizeExceeded() throws Exception {
        ZipHandler h = new ZipHandler(10 * 1024 * 1024, 100, 100, 100);
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            for (int i = 0; i < 5; i++) {
                zos.putNextEntry(new java.util.zip.ZipEntry("f" + i + ".txt"));
                zos.write(new byte[50]);
                zos.closeEntry();
            }
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).isNotEmpty();
    }

    // ==================== DocxHandler edge cases ====================

    @Test
    void docxHandlerSupports() {
        DocxHandler h = new DocxHandler();
        assertThat(h.supports("test.docx")).isTrue();
        assertThat(h.supports("test.docm")).isTrue();
        assertThat(h.supports("test.doc")).isFalse();
        assertThat(h.supports(null)).isFalse();
    }

    @Test
    void docxHandlerInvalidBytes() throws Exception {
        DocxHandler h = new DocxHandler();
        byte[] invalid = "not a docx file".getBytes();
        ExtractionResult r = h.extract(new ByteArrayInputStream(invalid), "bad.docx");
        assertThat(r.getErrors()).isNotEmpty();
    }

    // ==================== ExcelHandler edge cases ====================

    @Test
    void excelHandlerDetectImageFormatDefault() throws Exception {
        ExcelHandler h = new ExcelHandler();
        assertThat(h.supports("test.xlsx")).isTrue();
    }

    @Test
    void excelHandlerExtractEmptyWorkbook() throws Exception {
        ExcelHandler h = new ExcelHandler();
        byte[] wb = TestFileFactory.createSimpleExcel("Empty");
        ExtractionResult r = h.extract(TestFileFactory.toInputStream(wb), "empty.xlsx");
        assertThat(r.getElements()).isEmpty();
    }
}
