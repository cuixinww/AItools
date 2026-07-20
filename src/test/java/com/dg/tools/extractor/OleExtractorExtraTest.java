package com.dg.tools.extractor;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class OleExtractorExtraTest {

    @Test
    void shouldExtractOle10NativeWithLengthPrefix() throws Exception {
        byte[] content = "real content bytes".getBytes();
        byte[] ole = TestFileFactory.wrapAsOle10Native("file.bin", content);
        Map<String, byte[]> result = OleExtractor.extract(ole);
        assertThat(result).containsKey("file.bin");
        assertThat(result.get("file.bin")).isEqualTo(content);
    }

    @Test
    void shouldExtractMultipleDocumentsFromOle2Container() throws Exception {
        byte[] d1 = "doc-one".getBytes();
        byte[] d2 = "doc-two".getBytes();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            fs.getRoot().createDocument("One", new java.io.ByteArrayInputStream(d1));
            fs.getRoot().createDocument("Two", new java.io.ByteArrayInputStream(d2));
            fs.writeFilesystem(baos);
        }
        Map<String, byte[]> result = OleExtractor.extract(baos.toByteArray());
        // 非 Ole10Native 的 OLE2，应走到 extractAllDocuments 分支
        assertThat(result).isNotNull();
    }

    @Test
    void shouldReturnEmptyForNullInput() {
        assertThat(OleExtractor.extract(null)).isEmpty();
    }

    @Test
    void shouldReturnEmptyForRandomBytes() {
        byte[] random = new byte[200];
        for (int i = 0; i < random.length; i++) random[i] = (byte) (i * 7);
        // 既不是合法 OLE2 也不是合法 Ole10Native → 空
        assertThat(OleExtractor.extract(random)).isEmpty();
    }

    @Test
    void shouldExtractNestedOle10NativeInsideOle2() throws Exception {
        // OLE2 容器内某个流是 Ole10Native，应被解析出内部文件名与内容
        byte[] inner = TestFileFactory.createSimpleDocx("inner");
        byte[] ole10 = TestFileFactory.wrapAsOle10Native("inner.docx", inner);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            fs.getRoot().createDocument("Object1", new java.io.ByteArrayInputStream(ole10));
            fs.writeFilesystem(baos);
        }
        Map<String, byte[]> result = OleExtractor.extract(baos.toByteArray());
        assertThat(result).containsKey("Object1/inner.docx");
        assertThat(result.get("Object1/inner.docx")).isEqualTo(inner);
    }

    @Test
    void shouldHandleOle10NativeWithoutValidLengthPrefix() throws Exception {
        // 构造一个文件名后以裸内容结尾、没有 4 字节长度前缀的 Ole10Native
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] name = "raw.bin".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        int size = name.length + 1 + 4;
        baos.write(new byte[]{(byte) size, 0, 0, 0});
        baos.write(name);
        baos.write(0);
        baos.write(new byte[]{10, 20, 30, 40}); // 裸内容
        Map<String, byte[]> result = OleExtractor.extract(baos.toByteArray());
        assertThat(result).containsKey("raw.bin");
        assertThat(result.get("raw.bin")).containsExactly(10, 20, 30, 40);
    }
}
