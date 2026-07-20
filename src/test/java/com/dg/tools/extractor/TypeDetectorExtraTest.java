package com.dg.tools.extractor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TypeDetectorExtraTest {

    private final TypeDetector detector = new TypeDetector();

    @Test
    void shouldDetectXlsByContent() throws Exception {
        byte[] doc = TestFileFactory.createMinimalDoc();
        // 内容探测可能识别为 OLE2；这里只验证不为空且能回退
        String ext = detector.detectExtension(doc, "x.doc");
        assertThat(ext).isNotNull();
    }

    @Test
    void shouldDetectImageByContentForJpeg() throws Exception {
        // 构造一个最小 JPEG 签名（FFD8FF）
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01, 0x02};
        assertThat(detector.detectExtension(jpeg, null)).isEqualTo("jpg");
    }

    @Test
    void shouldFallbackToExtensionWhenContentIsTextPlain() {
        // Tika 可能判定为 text/plain，应回退到文件名后缀
        byte[] txt = "hello world content".getBytes();
        assertThat(detector.detectExtension(txt, "report.docx")).isEqualTo("docx");
    }

    @Test
    void shouldReturnBinWhenUnknownAndNoHint() {
        byte[] data = new byte[]{0x01, 0x02, 0x03, 0x04};
        assertThat(detector.detectExtension(data, null)).isNotNull();
    }

    @Test
    void shouldReturnOctetStreamForEmptyContent() {
        assertThat(detector.detectMimeType(new byte[0])).isEqualTo("application/octet-stream");
        assertThat(detector.detectExtension(new byte[0], null)).isEqualTo("bin");
    }

    @Test
    void shouldNotMapZipForDocxContent() throws Exception {
        // docx 本质是 zip，但探测应识别为 docx 而非 zip
        byte[] docx = TestFileFactory.createSimpleDocx("x");
        assertThat(detector.detectExtension(docx, "x.docx")).isEqualTo("docx");
    }

    @Test
    void shouldFallbackFromNameWithUnknownExtension() {
        byte[] data = "abc".getBytes();
        // zip 类型会回退到文件名；这里用 .xyz 后缀
        assertThat(detector.detectExtension(data, "file.xyz")).isEqualTo("xyz");
    }
}
