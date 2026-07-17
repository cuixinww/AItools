package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class ExcelHandlerTest {

    private final ExcelHandler handler = new ExcelHandler();

    // ========== Basic extraction ==========

    @Test
    void shouldInlineSmallTable() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("配置表",
                new String[]{"字段", "类型"},
                new String[]{"姓名", "string"},
                new String[]{"年龄", "int"}
        );
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "config.xlsx");

        assertThat(result.getElements()).hasSize(2);
        assertThat(result.getLargeTables()).isEmpty();
    }

    @Test
    void shouldSeparateLargeTable() throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("数据明细", 60, 5);
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "large.xlsx");

        assertThat(result.getLargeTables()).hasSize(1);
        assertThat(result.getLargeTables().get(0).getRowCount()).isEqualTo(60);
        assertThat(result.getLargeTables().get(0).getAllRows()).hasSize(60);
    }

    @Test
    void shouldHandleMultipleSheets() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("小表").createRow(0).createCell(0).setCellValue("A");
            wb.createSheet("大表").createRow(0).createCell(0).setCellValue("B");
            XSSFSheet bigSheet = wb.getSheet("大表");
            for (int i = 0; i < 60; i++) {
                bigSheet.createRow(i).createCell(0).setCellValue("x");
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); xlsx = baos.toByteArray(); }
        }

        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "multi.xlsx");

        assertThat(result.getElements()).isNotEmpty();
        assertThat(result.getLargeTables()).hasSize(1);
    }

    // ========== New: image extraction ==========

    @Test
    void shouldExtractImagesFromXlsx() throws Exception {
        byte[] pngBytes = TestFileFactory.createMinimalPng();
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("含图片");
            int picIdx = wb.addPicture(pngBytes, XSSFWorkbook.PICTURE_TYPE_PNG);
            var sheet = wb.getSheetAt(0);
            var drawing = sheet.createDrawingPatriarch();
            var anchor = wb.getCreationHelper().createClientAnchor();
            anchor.setCol1(0); anchor.setRow1(0);
            anchor.setCol2(1); anchor.setRow2(1);
            drawing.createPicture(anchor, picIdx);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); xlsx = baos.toByteArray(); }
        }

        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "with_image.xlsx");
        assertThat(result.getImages()).isNotEmpty();
    }

    // ========== Support ==========

    @Test
    void shouldSupportXlsx() {
        assertThat(handler.supports("test.xlsx")).isTrue();
        assertThat(handler.supports("test.xls")).isTrue();
        assertThat(handler.supports("test.docx")).isFalse();
    }
}
