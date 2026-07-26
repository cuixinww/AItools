package com.dg.tools.extractor;

import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OleExtractorTest {

    @Test
    void extractNullReturnsEmpty() {
        assertThat(OleExtractor.extract(null)).isEmpty();
    }

    @Test
    void extractEmptyReturnsEmpty() {
        assertThat(OleExtractor.extract(new byte[0])).isEmpty();
    }

    @Test
    void extractNonOleDataReturnsEmpty() {
        byte[] data = "not an OLE document at all".getBytes(StandardCharsets.UTF_8);
        assertThat(OleExtractor.extract(data)).isEmpty();
    }

    @Test
    void extractRawOle10NativeWithNameAndContent() {
        String fileName = "test.txt";
        String fileContent = "hello world";
        byte[] fileNameBytes = fileName.getBytes(StandardCharsets.ISO_8859_1);
        byte[] contentBytes = fileContent.getBytes(StandardCharsets.ISO_8859_1);

        byte[] oleData = new byte[4 + fileNameBytes.length + 1 + 4 + contentBytes.length];
        System.arraycopy(intToLeBytes(fileNameBytes.length), 0, oleData, 0, 4);
        System.arraycopy(fileNameBytes, 0, oleData, 4, fileNameBytes.length);
        oleData[4 + fileNameBytes.length] = 0;
        System.arraycopy(intToLeBytes(contentBytes.length), 0, oleData, 4 + fileNameBytes.length + 1, 4);
        System.arraycopy(contentBytes, 0, oleData, 4 + fileNameBytes.length + 1 + 4, contentBytes.length);

        Map<String, byte[]> result = OleExtractor.extract(oleData);
        assertThat(result).hasSize(1);
        String key = result.keySet().iterator().next();
        assertThat(key).contains(fileName);
        assertThat(result.get(key)).isEqualTo(contentBytes);
    }

    @Test
    void extractRawOle10NativeWithoutContentLength() {
        byte[] data = buildSimpleOle10Native("doc.txt", "content", false);
        Map<String, byte[]> result = OleExtractor.extract(data);
        assertThat(result).hasSize(1);
    }

    @Test
    void extractRawOle10NativeOnlyNameNoContent() {
        byte[] name = "test.doc".getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = new byte[4 + name.length + 1];
        System.arraycopy(intToLeBytes(name.length), 0, data, 0, 4);
        System.arraycopy(name, 0, data, 4, name.length);
        data[4 + name.length] = 0;

        Map<String, byte[]> result = OleExtractor.extract(data);
        assertThat(result).isEmpty();
    }

    @Test
    void extractOle10NativeContentLengthTooLarge() {
        byte[] name = "f.bin".getBytes(StandardCharsets.ISO_8859_1);
        byte[] data = new byte[4 + name.length + 1 + 4 + 10];
        System.arraycopy(intToLeBytes(name.length), 0, data, 0, 4);
        System.arraycopy(name, 0, data, 4, name.length);
        data[4 + name.length] = 0;
        System.arraycopy(intToLeBytes(999999), 0, data, 4 + name.length + 1, 4);

        Map<String, byte[]> result = OleExtractor.extract(data);
        assertThat(result).isNotEmpty();
    }

    @Test
    void extractDataTooShortForName() {
        byte[] data = new byte[]{0, 0, 0, 5};
        assertThat(OleExtractor.extract(data)).isEmpty();
    }

    /** Full Ole10Native format with srcPath + tmpPath between filename and content length. */
    @Test
    void extractFullOle10NativeWithSrcAndTmpPath() {
        String fileName = "embedded.docx";
        String srcPath = "C:\\Temp\\embedded.docx";
        String tmpPath = "C:\\Temp\\~$embedded.docx";
        byte[] content = "real content data".getBytes(StandardCharsets.ISO_8859_1);

        byte[] nameBytes = fileName.getBytes(StandardCharsets.ISO_8859_1);
        byte[] srcBytes = srcPath.getBytes(StandardCharsets.ISO_8859_1);
        byte[] tmpBytes = tmpPath.getBytes(StandardCharsets.ISO_8859_1);

        // Format: nativeSize(4LE) + name(NUL) + srcPath(NUL) + tmpPath(NUL) + dataSize(4LE) + content
        int total = 4 + nameBytes.length + 1 + srcBytes.length + 1 + tmpBytes.length + 1 + 4 + content.length;
        byte[] data = new byte[total];
        int off = 0;
        // nativeSize (name length)
        System.arraycopy(intToLeBytes(nameBytes.length), 0, data, off, 4);
        off += 4;
        // fileName + NUL
        System.arraycopy(nameBytes, 0, data, off, nameBytes.length);
        off += nameBytes.length;
        data[off++] = 0;
        // srcPath + NUL
        System.arraycopy(srcBytes, 0, data, off, srcBytes.length);
        off += srcBytes.length;
        data[off++] = 0;
        // tmpPath + NUL
        System.arraycopy(tmpBytes, 0, data, off, tmpBytes.length);
        off += tmpBytes.length;
        data[off++] = 0;
        // nativeDataSize
        System.arraycopy(intToLeBytes(content.length), 0, data, off, 4);
        off += 4;
        // content
        System.arraycopy(content, 0, data, off, content.length);

        Map<String, byte[]> result = OleExtractor.extract(data);
        assertThat(result).hasSize(1);
        String key = result.keySet().iterator().next();
        assertThat(key).contains(fileName);
        assertThat(result.values().iterator().next()).isEqualTo(content);
    }

    /** Full format where srcPath is exactly 4 chars — previously the old parser would
     *  misinterpret the first 4 bytes of srcPath as the content-length. */
    @Test
    void extractFullOle10NativeWithShort4ByteSrcPath() {
        String fileName = "e.xls";
        String srcPath = "C:\\f"; // exactly 4 bytes
        String tmpPath = "T:\\t";
        byte[] content = "important data".getBytes(StandardCharsets.ISO_8859_1);

        byte[] nameBytes = fileName.getBytes(StandardCharsets.ISO_8859_1);
        byte[] srcBytes = srcPath.getBytes(StandardCharsets.ISO_8859_1);
        byte[] tmpBytes = tmpPath.getBytes(StandardCharsets.ISO_8859_1);

        int total = 4 + nameBytes.length + 1 + srcBytes.length + 1 + tmpBytes.length + 1 + 4 + content.length;
        byte[] data = new byte[total];
        int off = 0;
        System.arraycopy(intToLeBytes(nameBytes.length), 0, data, off, 4);
        off += 4;
        System.arraycopy(nameBytes, 0, data, off, nameBytes.length);
        off += nameBytes.length;
        data[off++] = 0;
        System.arraycopy(srcBytes, 0, data, off, srcBytes.length);
        off += srcBytes.length;
        data[off++] = 0;
        System.arraycopy(tmpBytes, 0, data, off, tmpBytes.length);
        off += tmpBytes.length;
        data[off++] = 0;
        System.arraycopy(intToLeBytes(content.length), 0, data, off, 4);
        off += 4;
        System.arraycopy(content, 0, data, off, content.length);

        Map<String, byte[]> result = OleExtractor.extract(data);
        assertThat(result).hasSize(1);
        assertThat(result.values().iterator().next()).isEqualTo(content);
    }

    @Test
    void extractOle2ContainerFallsBackToUserStreams() throws Exception {
        byte[] oleData;
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             POIFSFileSystem fs = new POIFSFileSystem()) {
            fs.getRoot().createDocument("user.txt", new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)));
            fs.getRoot().createDocument("\u0001CompObj", new ByteArrayInputStream("ignored".getBytes(StandardCharsets.UTF_8)));
            fs.writeFilesystem(baos);
            oleData = baos.toByteArray();
        }

        Map<String, byte[]> result = OleExtractor.extract(oleData);
        assertThat(result).containsKey("user.txt");
        assertThat(result.get("user.txt")).isEqualTo("hello".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] buildSimpleOle10Native(String fileName, String content, boolean includeLength) {
        byte[] nameBytes = fileName.getBytes(StandardCharsets.ISO_8859_1);
        byte[] contentBytes = content.getBytes(StandardCharsets.ISO_8859_1);

        int totalLen;
        if (includeLength) {
            totalLen = 4 + nameBytes.length + 1 + 4 + contentBytes.length;
        } else {
            totalLen = 4 + nameBytes.length + 1 + contentBytes.length;
        }

        byte[] data = new byte[totalLen];
        System.arraycopy(intToLeBytes(nameBytes.length), 0, data, 0, 4);
        System.arraycopy(nameBytes, 0, data, 4, nameBytes.length);
        data[4 + nameBytes.length] = 0;

        int offset = 4 + nameBytes.length + 1;
        if (includeLength) {
            System.arraycopy(intToLeBytes(contentBytes.length), 0, data, offset, 4);
            offset += 4;
        }
        System.arraycopy(contentBytes, 0, data, offset, contentBytes.length);

        return data;
    }

    private static byte[] intToLeBytes(int value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }
}
