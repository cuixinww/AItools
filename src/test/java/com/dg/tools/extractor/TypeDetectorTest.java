package com.dg.tools.extractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class TypeDetectorTest {

    private TypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new TypeDetector();
    }

    @Test
    void detectExtensionNullData() {
        assertThat(detector.detectExtension(null, "test.docx")).isEqualTo("docx");
    }

    @Test
    void detectExtensionEmptyData() {
        assertThat(detector.detectExtension(new byte[0], "test.pdf")).isEqualTo("pdf");
    }

    @Test
    void detectExtensionNullDataNullHint() {
        assertThat(detector.detectExtension(null, null)).isEqualTo("bin");
    }

    @Test
    void detectExtensionPdf() throws Exception {
        Path pdfPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.pdf");
        byte[] data = Files.readAllBytes(pdfPath);
        assertThat(detector.detectExtension(data, "test.pdf")).isEqualTo("pdf");
    }

    @Test
    void detectExtensionDocxByContent() throws Exception {
        Path docxPath = Path.of("doc/SCM-NextGen_软件需求规格说明书_V2.3.1.docx");
        byte[] data = Files.readAllBytes(docxPath);
        String ext = detector.detectExtension(data, "test.docx");
        assertThat(ext).isEqualTo("docx");
    }

    @Test
    void detectExtensionPngMagic() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        assertThat(detector.detectExtension(png, "test.bin")).isEqualTo("png");
    }

    @Test
    void detectExtensionOctetStreamFallback() {
        byte[] random = {0, 1, 2, 3, 4, 5};
        assertThat(detector.detectExtension(random, "report.docx")).isEqualTo("docx");
    }

    @Test
    void detectExtensionOctetStreamFallbackToBin() {
        byte[] random = {0, 1, 2, 3, 4, 5};
        assertThat(detector.detectExtension(random, null)).isEqualTo("bin");
    }

    @Test
    void detectMimeTypeNull() {
        assertThat(detector.detectMimeType(null)).isEqualTo("application/octet-stream");
    }

    @Test
    void detectMimeTypeEmpty() {
        assertThat(detector.detectMimeType(new byte[0])).isEqualTo("application/octet-stream");
    }

    @Test
    void detectMimeTypePdf() throws Exception {
        Path pdfPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.pdf");
        byte[] data = Files.readAllBytes(pdfPath);
        assertThat(detector.detectMimeType(data)).isEqualTo("application/pdf");
    }

    @Test
    void detectMimeTypeDocx() throws Exception {
        Path docxPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.docx");
        byte[] data = Files.readAllBytes(docxPath);
        // OOXML 类型仅凭魔数字节无法区分 docx/xlsx/pptx，Tika 返回通用 x-tika-ooxml
        assertThat(detector.detectMimeType(data))
                .isEqualTo("application/x-tika-ooxml");
    }

    @Test
    void detectExtensionDocx() throws Exception {
        Path docxPath = Path.of("doc/CIT_F065_用户需求说明书_AI-BAU需求管控工具.docx");
        byte[] data = Files.readAllBytes(docxPath);
        // detectExtension 应回退到文件名后缀
        assertThat(detector.detectExtension(data, "test.docx")).isEqualTo("docx");
    }

    @Test
    void detectExtensionXlsx() throws Exception {
        Path xlsxPath = Path.of("doc/保险理赔需求设计.xlsx");
        byte[] data = Files.readAllBytes(xlsxPath);
        // xlsx 也是 OOXML zip，应回退到文件名后缀
        assertThat(detector.detectExtension(data, "test.xlsx")).isEqualTo("xlsx");
    }

    @Test
    void detectMimeTypeZipContainer() throws Exception {
        Path xlsxPath = Path.of("doc/保险理赔需求设计.xlsx");
        byte[] data = Files.readAllBytes(xlsxPath);
        String mime = detector.detectMimeType(data);
        assertThat(mime).isEqualTo("application/zip");
    }

    @Test
    void detectMimeTypeFallsBackToOctetStream() {
        assertThat(detector.detectMimeType(new byte[]{1,2,3,4})).isEqualTo("application/octet-stream");
    }

    @Test
    void detectExtensionUsesHintForUnknownBin() {
        assertThat(detector.detectExtension(new byte[]{1,2,3}, "report.docx")).isEqualTo("docx");
    }

    @Test
    void detectExtensionZipContainerReturnsXlsx() throws Exception {
        Path xlsxPath = Path.of("doc/保险理赔需求设计.xlsx");
        byte[] data = Files.readAllBytes(xlsxPath);
        String ext = detector.detectExtension(data, "test.xlsx");
        assertThat(ext).isEqualTo("xlsx");
    }

    @Test
    void detectExtensionFallbackFromNameNoDot() {
        assertThat(detector.detectExtension(new byte[]{0, 1, 2}, "file")).isEqualTo("bin");
    }

    @Test
    void fallbackFromNameWithPath() {
        assertThat(detector.detectExtension(null, "/path/to/document.PDF")).isEqualTo("pdf");
    }
}
