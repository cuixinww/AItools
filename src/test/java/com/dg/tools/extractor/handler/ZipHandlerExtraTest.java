package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.UnpackResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.*;

class ZipHandlerExtraTest {

    private final ZipHandler handler = new ZipHandler();

    private byte[] makeZip(java.util.Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (var e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }

    @Test
    void shouldUnpackAndExtractSameEntries() throws Exception {
        byte[] zip = makeZip(java.util.Map.of("a.txt", "AAA".getBytes(), "b.txt", "BBB".getBytes()));
        UnpackResult ur = handler.unpack(TestFileFactory.toInputStream(zip), "t.zip");
        ExtractionResult er = handler.extract(TestFileFactory.toInputStream(zip), "t.zip");
        assertThat(ur.getEmbeddedFiles()).hasSize(2);
        assertThat(er.getEmbeddedFiles()).hasSize(2);
    }

    @Test
    void shouldRejectPathTraversal() throws Exception {
        byte[] zip = makeZip(java.util.Map.of("../evil.txt", "x".getBytes()));
        ExtractionResult er = handler.extract(TestFileFactory.toInputStream(zip), "t.zip");
        // 路径穿越条目应被拒绝（不出现）
        assertThat(er.getEmbeddedFiles()).noneMatch(f -> f.getFileName().contains(".."));
    }

    @Test
    void shouldRejectAbsolutePathEntry() throws Exception {
        byte[] zip = makeZip(java.util.Map.of("/abs.txt", "x".getBytes()));
        ExtractionResult er = handler.extract(TestFileFactory.toInputStream(zip), "t.zip");
        assertThat(er.getEmbeddedFiles()).noneMatch(f -> f.getFileName().startsWith("/"));
    }

    @Test
    void shouldSkipDirectories() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("folder/"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("folder/file.txt"));
            zos.write("x".getBytes());
            zos.closeEntry();
        }
        ExtractionResult er = handler.extract(TestFileFactory.toInputStream(baos.toByteArray()), "t.zip");
        assertThat(er.getEmbeddedFiles()).hasSize(1);
        assertThat(er.getEmbeddedFiles().get(0).getFileName()).isEqualTo("folder/file.txt");
    }

    @Test
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "t.zip"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "t.zip"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotSupportNonZip() {
        assertThat(handler.supports("a.docx")).isFalse();
        assertThat(handler.supports("a.ZIP")).isTrue();
    }
}
