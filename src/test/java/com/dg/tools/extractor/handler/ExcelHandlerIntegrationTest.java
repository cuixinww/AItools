package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

class ExcelHandlerIntegrationTest {

    private final ExcelHandler handler = new ExcelHandler();

    @Test
    void extractRealXlsx() throws Exception {
        Path xlsxPath = Path.of("doc/保险理赔需求设计.xlsx");
        assertThat(xlsxPath).exists();
        try (InputStream is = Files.newInputStream(xlsxPath)) {
            ExtractionResult result = handler.extract(is, xlsxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("xlsx");
            assertThat(result.getElements()).isNotEmpty();
        }
    }

    @Test
    void unpackRealXlsx() throws Exception {
        Path xlsxPath = Path.of("doc/保险理赔需求设计.xlsx");
        try (InputStream is = Files.newInputStream(xlsxPath)) {
            ExtractionResult result = handler.unpack(is, xlsxPath.getFileName().toString());
            assertThat(result.getFileType()).isEqualTo("xlsx");
        }
    }

    @Test
    void supportsXlsx() {
        assertThat(handler.supports("test.xlsx")).isTrue();
        assertThat(handler.supports("test.xls")).isTrue();
        assertThat(handler.supports(null)).isFalse();
    }
}
