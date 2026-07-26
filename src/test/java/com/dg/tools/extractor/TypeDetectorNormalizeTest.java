package com.dg.tools.extractor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class TypeDetectorNormalizeTest {

    private TypeDetector detector;

    @BeforeEach
    void setUp() {
        detector = new TypeDetector();
    }

    // Uncovered: normalizeMimeType method (lines 123-130)
    @Test
    void detectExtensionNormalizesMime() throws Exception {
        Path pngPath = Path.of("doc/保险理赔需求设计.xlsx");
        byte[] data = Files.readAllBytes(pngPath);
        // XLSX is application/zip -> should resolve to xlsx via hint
        String ext = detector.detectExtension(data, "test.xlsx");
        assertThat(ext).isEqualTo("xlsx");
    }

    @Test
    void fallbackFromNameWithDotAtEnd() {
        // hint ends with dot -> no valid extension
        String ext = detector.detectExtension(null, "filename.");
        assertThat(ext).isEqualTo("bin");
    }

    @Test
    void fallbackFromNameOnlyDots() {
        assertThat(detector.detectExtension(null, "...")).isEqualTo("bin");
    }

    @Test
    void detectMimeTypeErrorPath() throws Exception {
        // Pass data that might cause Tika internal error
        byte[] result = new byte[]{(byte) 0xFF, (byte) 0xFE, 0, 1};
        String mime = detector.detectMimeType(result);
        // Should never throw — always returns octet-stream on error
        assertThat(mime).isNotNull();
    }
}
