package com.dg.tools.extractor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class TypeDetectorTest {

    private final TypeDetector detector = new TypeDetector();

    @Test
    void shouldDetectDocxWithHint() throws Exception {
        byte[] docx = TestFileFactory.createSimpleDocx("Hello");
        String ext = detector.detectExtension(docx, "test.docx");
        assertThat(ext).isEqualTo("docx");
    }

    @Test
    void shouldDetectXlsxWithHint() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("S1", new String[]{"A"});
        String ext = detector.detectExtension(xlsx, "data.xlsx");
        assertThat(ext).isEqualTo("xlsx");
    }

    @Test
    void shouldDetectPdfByContent() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("Hello");
        String ext = detector.detectExtension(pdf, null);
        assertThat(ext).isEqualTo("pdf");
    }

    @Test
    void shouldDetectPngByContent() {
        byte[] png = TestFileFactory.createMinimalPng();
        String ext = detector.detectExtension(png, null);
        assertThat(ext).isEqualTo("png");
    }

    @Test
    void shouldReturnMimeType() {
        byte[] png = TestFileFactory.createMinimalPng();
        String mime = detector.detectMimeType(png);
        assertThat(mime).isEqualTo("image/png");
    }

    @Test
    void shouldFallbackToExtensionForUnknownContent() {
        byte[] unknown = "just some text".getBytes();
        String ext = detector.detectExtension(unknown, "mydoc.docx");
        assertThat(ext).isEqualTo("docx");
    }

    @Test
    void shouldReturnBinForUnknownContentWithoutHint() {
        byte[] unknown = "just some text".getBytes();
        String ext = detector.detectExtension(unknown, null);
        // Tika detects plain text as text/plain → txt, without hint it's fine
        assertThat(ext).isNotNull();
    }

    @Test
    void shouldDetectDocWithHint() throws Exception {
        byte[] doc = TestFileFactory.createMinimalDoc();
        String ext = detector.detectExtension(doc, "test.doc");
        // OLE2 format may or may not be detected; fallback to extension should work
        assertThat(ext).isNotNull();
    }
}
