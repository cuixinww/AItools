package com.dg.tools.extractor;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import com.dg.tools.extractor.handler.ImageHandler;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ImageHandlerTest {

    private final ImageHandler handler = new ImageHandler();

    @Test
    void shouldSupportPng() {
        assertThat(handler.supports("image.png")).isTrue();
        assertThat(handler.supports("photo.PNG")).isTrue();
    }

    @Test
    void shouldSupportJpg() {
        assertThat(handler.supports("photo.jpg")).isTrue();
        assertThat(handler.supports("photo.jpeg")).isTrue();
    }

    @Test
    void shouldSupportGif() {
        assertThat(handler.supports("anim.gif")).isTrue();
    }

    @Test
    void shouldSupportBmp() {
        assertThat(handler.supports("bitmap.bmp")).isTrue();
    }

    @Test
    void shouldSupportWebp() {
        assertThat(handler.supports("img.webp")).isTrue();
    }

    @Test
    void shouldSupportTiff() {
        assertThat(handler.supports("img.tiff")).isTrue();
        assertThat(handler.supports("img.tif")).isTrue();
    }

    @Test
    void shouldNotSupportNonImage() {
        assertThat(handler.supports("doc.docx")).isFalse();
        assertThat(handler.supports("sheet.xlsx")).isFalse();
        assertThat(handler.supports("doc.pdf")).isFalse();
        assertThat(handler.supports("file.zip")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldExtractPngAsImageFile() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(png), "test.png");

        assertThat(result.getFileType()).isEqualTo("image");
        assertThat(result.getFileName()).isEqualTo("test.png");
        assertThat(result.getImages()).hasSize(1);

        ImageFile img = result.getImages().get(0);
        assertThat(img.getFileName()).isEqualTo("test.png");
        assertThat(img.getFormat()).isEqualTo("png");
        assertThat(img.getData()).isEqualTo(png);
        assertThat(img.getPosition()).isEqualTo(0);
    }

    @Test
    void shouldHaveNoElements() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(png), "test.png");

        assertThat(result.getElements()).isEmpty();
        assertThat(result.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void shouldDetectFormatFromExtension() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(png), "photo.jpg");
        assertThat(result.getImages().get(0).getFormat()).isEqualTo("jpg");
    }
}
