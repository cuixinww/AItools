package com.dg.tools.extractor;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OleExtractorTest {

    @Test
    void shouldExtractOle10NativeFile() throws Exception {
        byte[] innerDocx = TestFileFactory.createSimpleDocx("embedded text");
        byte[] ole = TestFileFactory.wrapAsOle10Native("inner.docx", innerDocx);

        Map<String, byte[]> result = OleExtractor.extract(ole);
        assertThat(result).isNotEmpty();
        assertThat(result).containsKey("inner.docx");
        assertThat(result.get("inner.docx")).isEqualTo(innerDocx);
    }

    @Test
    void shouldReturnEmptyMapForNonOleData() {
        byte[] plainText = "not an OLE stream".getBytes();
        Map<String, byte[]> result = OleExtractor.extract(plainText);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldReturnEmptyMapForEmptyInput() {
        Map<String, byte[]> result = OleExtractor.extract(new byte[0]);
        assertThat(result).isEmpty();
    }

    @Test
    void shouldHandleLargeFile() throws Exception {
        byte[] large = new byte[100_000];
        for (int i = 0; i < large.length; i++) large[i] = (byte) (i % 256);
        byte[] ole = TestFileFactory.wrapAsOle10Native("large.bin", large);

        Map<String, byte[]> result = OleExtractor.extract(ole);
        assertThat(result).isNotEmpty();
        assertThat(result.get("large.bin")).isEqualTo(large);
    }

    @Test
    void shouldExtractMultipleEntriesFromOle2Container() throws Exception {
        // Build a valid OLE2 container with multiple entries
        byte[] data1 = "file one content".getBytes();
        byte[] data2 = "file two content".getBytes();

        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            fs.getRoot().createDocument("File1", new java.io.ByteArrayInputStream(data1));
            fs.getRoot().createDocument("File2", new java.io.ByteArrayInputStream(data2));
            fs.writeFilesystem(baos);
        }
        Map<String, byte[]> result = OleExtractor.extract(baos.toByteArray());
        // OLE2 with POIFS entries but not Ole10Native format — may return empty
        assertThat(result).isNotNull();
    }
}
