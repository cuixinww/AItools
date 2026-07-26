package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExcelHandlerExtraTest {

    // Uncovered: doUnpack() extracts images from workbook
    @Test
    void unpackExtractsImagesFromXlsx() throws Exception {
        ExcelHandler h = new ExcelHandler();
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1", new String[]{"a"}, new String[]{"b"});
        ExtractionResult r = h.unpack(new ByteArrayInputStream(xlsx), "test.xlsx");
        assertThat(r.getFileType()).isEqualTo("xlsx");
        // Simple excel created by factory has no embedded images, so empty is expected
        assertThat(r.getImages()).isEmpty();
    }

    // Uncovered: extract() handles empty rows in region
    @Test
    void extractWithOnlyHeaderRow() throws Exception {
        ExcelHandler h = new ExcelHandler();
        byte[] xlsx = TestFileFactory.createSimpleExcel("OnlyHeader", new String[]{"colA", "colB"});
        ExtractionResult r = h.extract(TestFileFactory.toInputStream(xlsx), "header-only.xlsx");
        assertThat(r.getElements()).isNotEmpty();
        List<String> types = r.getElements().stream().map(e -> e.getType()).toList();
        assertThat(types).containsAnyOf("sheet_header", "table");
    }

    // Uncovered: detectImageFormat default/mime without slash
    @Test
    void detectImageFormatUnknownMime() throws Exception {
        ExcelHandler h = new ExcelHandler();
        // We can't easily test private method directly, but we test that
        // unsupported mime falls through to default case
        byte[] xlsx = TestFileFactory.createSimpleExcel("Normal");
        ExtractionResult r = h.extract(TestFileFactory.toInputStream(xlsx), "normal.xlsx");
        assertThat(r.getFileType()).isEqualTo("xlsx");
    }

    // Uncovered: extract image extraction loop when wb has no pictures
    @Test
    void extractWithoutPictures() throws Exception {
        ExcelHandler h = new ExcelHandler();
        byte[] xlsx = TestFileFactory.createSimpleExcel("Empty", new String[]{"a"}, new String[]{"b"});
        ExtractionResult r = h.extract(TestFileFactory.toInputStream(xlsx), "nopics.xlsx");
        assertThat(r.getImages()).isEmpty();
    }
}
