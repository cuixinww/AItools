package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ImageHandlerExtraTest {

    private final ImageHandler handler = new ImageHandler();

    @Test
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "a.png"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "a.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectOversizedImage() throws Exception {
        byte[] big = new byte[101 * 1024 * 1024];
        assertThatThrownBy(() -> handler.extract(TestFileFactory.toInputStream(big), "big.png"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldUnpackImageAsWell() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        var ur = handler.unpack(TestFileFactory.toInputStream(png), "a.png");
        assertThat(ur.getImages()).hasSize(1);
        assertThat(ur.getImages().get(0).getFormat()).isEqualTo("png");
    }

    @Test
    void shouldNormalizeTifToTiff() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = handler.extract(TestFileFactory.toInputStream(png), "photo.tif");
        assertThat(r.getImages().get(0).getFormat()).isEqualTo("tiff");
    }

    @Test
    void shouldNormalizeJpegToJpg() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = handler.extract(TestFileFactory.toInputStream(png), "photo.jpeg");
        assertThat(r.getImages().get(0).getFormat()).isEqualTo("jpg");
    }

    @Test
    void shouldNotSupportWithoutExtension() {
        assertThat(handler.supports("noext")).isFalse();
    }
}
