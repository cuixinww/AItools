package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ImageHandlerExtraTest {

    // Uncovered: doUnpack() — unpack image, max-image check, exception path
    @Test
    void unpackImageNormal() throws Exception {
        ImageHandler h = new ImageHandler(100);
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = h.unpack(new ByteArrayInputStream(png), "test.png");
        assertThat(r.getImages()).hasSize(1);
        assertThat(r.getFileType()).isEqualTo("image");
    }

    @Test
    void unpackImageTooLarge() throws Exception {
        ImageHandler h = new ImageHandler(10);
        byte[] data = new byte[50];
        ExtractionResult r = h.unpack(new ByteArrayInputStream(data), "big.png");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("image too large");
    }

    @Test
    void extractImageErrorPath() throws Exception {
        ImageHandler h = new ImageHandler(1);  // max 1 byte
        byte[] data = new byte[50];
        ExtractionResult r = h.extract(new ByteArrayInputStream(data), "big.png");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("image too large");
    }

    @Test
    void extractImageNormal() throws Exception {
        ImageHandler h = new ImageHandler(100);
        byte[] png = TestFileFactory.createMinimalPng();
        ExtractionResult r = h.extract(new ByteArrayInputStream(png), "ok.png");
        assertThat(r.getImages()).hasSize(1);
        assertThat(r.getImages().get(0).getData()).isEqualTo(png);
    }
}
