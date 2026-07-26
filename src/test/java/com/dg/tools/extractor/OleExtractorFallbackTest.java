package com.dg.tools.extractor;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OleExtractorFallbackTest {

    @Test
    void extractNonOleContainerWithRawOle10Native() throws Exception {
        // Build raw Ole10Native: 4-byte length + "file.txt" + NUL + content
        byte[] nameBytes = "test.bin".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] contentBytes = "raw content".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int totalLen = 4 + nameBytes.length + 1 + contentBytes.length;
        byte[] oleData = new byte[totalLen];

        // Name length prefix (little-endian)
        oleData[0] = (byte) (nameBytes.length & 0xFF);
        oleData[1] = (byte) ((nameBytes.length >> 8) & 0xFF);
        oleData[2] = (byte) ((nameBytes.length >> 16) & 0xFF);
        oleData[3] = (byte) ((nameBytes.length >> 24) & 0xFF);
        System.arraycopy(nameBytes, 0, oleData, 4, nameBytes.length);
        oleData[4 + nameBytes.length] = 0; // NUL terminator
        System.arraycopy(contentBytes, 0, oleData, 4 + nameBytes.length + 1, contentBytes.length);

        Map<String, byte[]> result = OleExtractor.extract(oleData);
        assertThat(result).hasSize(1);
        String key = result.keySet().iterator().next();
        assertThat(key).contains("test.bin");
        assertThat(result.get(key)).isEqualTo(contentBytes);
    }

    @Test
    void extractNonOleTooShortForNameReturnEmpty() {
        byte[] data = new byte[]{0, 0, 0};
        assertThat(OleExtractor.extract(data)).isEmpty();
    }

    @Test
    void extractNonOleNoNameReturnsEmpty() {
        // Just the name length field but no actual name bytes
        byte[] data = new byte[]{4, 0, 0, 0};
        assertThat(OleExtractor.extract(data)).isEmpty();
    }

    @Test
    void extractValidOleContainerFallsBackToUserStreams() throws Exception {
        // Build a minimal OLE2 container with a simple user document stream
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.createDocument(new java.io.ByteArrayInputStream("user data".getBytes()), "UserFile");
            fs.writeFilesystem(baos);
        }
        byte[] oleData = baos.toByteArray();

        Map<String, byte[]> result = OleExtractor.extract(oleData);
        // Should find "UserFile" since there's no Ole10Native entries
        assertThat(result).hasSizeGreaterThanOrEqualTo(1);
    }
}
