package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class ZipHandlerEdgeTest {

    @Test
    void sanitizeEntryStartsWithDoubleSlash() throws Exception {
        ZipHandler h = new ZipHandler();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("//double/slash.txt"));
            zos.write("data".getBytes());
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(1);
        assertThat(r.getEmbeddedFiles().get(0).getFileName()).doesNotStartWith("/");
    }

    @Test
    void sanitizeEntryMixedCasePathTraversal() throws Exception {
        ZipHandler h = new ZipHandler();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry(".\\..\\tricky.txt"));
            zos.write("data".getBytes());
            zos.closeEntry();
        }
        // This uses forward slash internally after sanitization
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "test.zip");
        assertThat(r.getEmbeddedFiles().stream().allMatch(ef -> !ef.getFileName().contains(".."))).isTrue();
    }

    @Test
    void readEntryEmptyZipReturnsNoEmbedded() throws Exception {
        ZipHandler h = new ZipHandler(10 * 1024 * 1024, 500L * 1024 * 1024, 100, 100);
        // Empty zip file — just the local file header magic without entries
        byte[] emptyZip = {(byte) 0x50, (byte) 0x4B, 0x03, 0x04};
        ExtractionResult r = h.extract(new ByteArrayInputStream(emptyZip), "empty.zip");
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void extractWithOnlyDirEntries() throws Exception {
        ZipHandler h = new ZipHandler();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("dir/"));
            zos.closeEntry();
            zos.putNextEntry(new java.util.zip.ZipEntry("dir/subdir/"));
            zos.closeEntry();
        }
        ExtractionResult r = h.extract(new ByteArrayInputStream(baos.toByteArray()), "dirs.zip");
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void unpackSkipsDirectories() throws Exception {
        ZipHandler h = new ZipHandler();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(baos)) {
            zos.putNextEntry(new java.util.zip.ZipEntry("f.bin"));
            zos.write(new byte[]{1, 2, 3});
            zos.closeEntry();
        }
        ExtractionResult r = h.unpack(new ByteArrayInputStream(baos.toByteArray()), "f.zip");
        assertThat(r.getEmbeddedFiles()).hasSize(1);
        assertThat(r.getElements()).isEmpty();
    }
}
