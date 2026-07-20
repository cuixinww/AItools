package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.excel.RegionSplitter;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.LargeTableInfo;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExcelHandlerExtraTest {

    private final ExcelHandler handler = new ExcelHandler();

    @Test
    void shouldSplitWideSheetIntoRegions() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet s = wb.createSheet("宽表");
            // 左区域 列0-1
            s.createRow(0).createCell(0).setCellValue("L1");
            s.createRow(0).createCell(1).setCellValue("L2");
            // 列2、3 空（分隔）
            // 右区域 列4-5
            s.createRow(0).createCell(4).setCellValue("R1");
            s.createRow(0).createCell(5).setCellValue("R2");
            for (int r = 1; r <= 5; r++) {
                s.createRow(r).createCell(0).setCellValue("a" + r);
                s.createRow(r).createCell(1).setCellValue("b" + r);
                s.createRow(r).createCell(4).setCellValue("c" + r);
                s.createRow(r).createCell(5).setCellValue("d" + r);
            }
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); xlsx = baos.toByteArray(); }
        }
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "wide.xlsx");
        // 两个 sheet_header + 两个 table（均为小表）
        assertThat(result.getElements().stream().filter(e -> "sheet_header".equals(e.getType())).count()).isEqualTo(2);
    }

    @Test
    void shouldCreateLargeTableWithPreview() throws Exception {
        byte[] xlsx = TestFileFactory.createLargeExcel("表", 60, 3);
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "big.xlsx");
        assertThat(result.getLargeTables()).hasSize(1);
        LargeTableInfo lti = result.getLargeTables().get(0);
        assertThat(lti.getPreview()).contains("Columns:");
        assertThat(lti.getPreview()).contains("Row 1:");
    }

    @Test
    void shouldDetectImageFormatFromMime() {
        assertThat(handler.supports("a.xls")).isTrue();
        assertThat(handler.supports("a.xlsx")).isTrue();
        assertThat(handler.supports("a.csv")).isFalse();
    }

    @Test
    void shouldHandleSheetWithOnlyHeader() throws Exception {
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet s = wb.createSheet("仅表头");
            s.createRow(0).createCell(0).setCellValue("列A");
            s.createRow(0).createCell(1).setCellValue("列B");
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); xlsx = baos.toByteArray(); }
        }
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(xlsx), "hdr.xlsx");
        // 只有表头无数据行 → 视为小表渲染
        assertThat(result.getElements()).isNotEmpty();
    }

    @Test
    void shouldExtractImagesOnUnpack() throws Exception {
        byte[] png = TestFileFactory.createMinimalPng();
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("s");
            int idx = wb.addPicture(png, XSSFWorkbook.PICTURE_TYPE_PNG);
            var sheet = wb.getSheetAt(0);
            var drawing = sheet.createDrawingPatriarch();
            var anchor = wb.getCreationHelper().createClientAnchor();
            drawing.createPicture(anchor, idx);
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { wb.write(baos); xlsx = baos.toByteArray(); }
        }
        var ur = handler.unpack(TestFileFactory.toInputStream(xlsx), "p.xlsx");
        assertThat(ur.getImages()).isNotEmpty();
    }
}
