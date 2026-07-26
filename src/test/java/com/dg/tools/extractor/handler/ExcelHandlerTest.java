package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.LargeTableInfo;
import com.dg.tools.extractor.handler.ExcelHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExcelHandlerTest {

    private final ExcelHandler handler = new ExcelHandler();

    @Test
    void shouldSupportXlsx() {
        assertThat(handler.supports("test.xlsx")).isTrue();
        assertThat(handler.supports("test.xls")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }

    @Test
    void shouldExtractSmallTable() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1",
                new String[]{"Name", "Age"},
                new String[]{"Alice", "30"},
                new String[]{"Bob", "25"}
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "test.xlsx");

        List<Element> tables = result.getElements().stream()
                .filter(e -> "table".equals(e.getType())).toList();
        assertThat(tables).isNotEmpty();
        assertThat(tables.get(0).getContent()).contains("Name").contains("Alice").contains("Bob");
    }

    @Test
    void shouldHandleLargeTableAsDataRef() throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("LargeSheet", 60, 5);
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "large.xlsx");

        assertThat(result.getLargeTables()).isNotEmpty();
        LargeTableInfo lti = result.getLargeTables().get(0);
        assertThat(lti.getRowCount()).isEqualTo(60);
        assertThat(lti.getAllRows()).hasSize(60);
    }

    @Test
    void shouldHandleEmptySheet() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Empty");
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "empty.xlsx");
        assertThat(result.getElements()).isEmpty();
    }

    @Test
    void shouldHandleNullFileName() {
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldUseHeaderRowWithMultipleNonEmptyCells() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1",
                new String[]{"Name", "Age", "City"},
                new String[]{"Alice", "30", "Beijing"},
                new String[]{"Bob", "25", "Shanghai"}
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "header.xlsx");
        assertThat(result.getElements()).isNotEmpty();
        assertThat(result.getElements().get(0).getType()).isEqualTo("sheet_header");
    }

    @Test
    void shouldHandleSheetWithOnlySingleNonEmptyRow() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1",
                new String[]{"Only", "Row"}
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "single.xlsx");
        assertThat(result.getElements()).isNotEmpty();
    }

    @Test
    void shouldSplitWideSheetIntoRegions() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("Sheet1",
                new String[]{"A1", "B1", "C1", "D1", "E1", "F1"},
                new String[]{"1", "2", "3", "4", "5", "6"}
        );

        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "wide.xlsx");

        assertThat(result.getElements()).isNotEmpty();
        assertThat(result.getElements().get(0).getType()).isEqualTo("sheet_header");
    }

    @Test
    void shouldGeneratePreviewForLargeWideTable() throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("WideSheet", 3, 15);

        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "wide-large.xlsx");

        assertThat(result.getLargeTables()).isNotEmpty();
        assertThat(result.getLargeTables().get(0).getPreview()).contains("Columns:");
    }
}
