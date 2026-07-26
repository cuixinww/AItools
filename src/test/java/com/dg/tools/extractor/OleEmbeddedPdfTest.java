package com.dg.tools.extractor;

import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OleEmbeddedPdfTest {

    @Test
    void extractPdfFromOleContainer() throws Exception {
        byte[] oleBytes;
        Path docxPath = Path.of("doc/保险项目用户需求说明书.docx");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(docxPath.toFile())) {
            var entry = zip.getEntry("word/embeddings/oleObject1.bin");
            try (InputStream in = zip.getInputStream(entry)) {
                oleBytes = in.readAllBytes();
            }
        }

        Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);

        assertThat(extracted).isNotEmpty();
        String key = extracted.keySet().iterator().next();
        byte[] content = extracted.get(key);

        // Verify it's a real PDF (magic bytes %PDF)
        assertThat(content.length).isGreaterThan(100);
        assertThat(content[0]).isEqualTo((byte) 0x25); // '%'
        assertThat(content[1]).isEqualTo((byte) 0x50); // 'P'
        assertThat(content[2]).isEqualTo((byte) 0x44); // 'D'
        assertThat(content[3]).isEqualTo((byte) 0x46); // 'F'

        // Verify filename is readable (no control chars in key)
        assertThat(key).doesNotContain("\u0001").doesNotContain("\u0002");
        assertThat(key).contains(".pdf");
    }
}