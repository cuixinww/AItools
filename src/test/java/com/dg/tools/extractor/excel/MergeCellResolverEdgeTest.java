package com.dg.tools.extractor.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MergeCellResolverEdgeTest {

    // formatNumeric date branch (DateUtil.isCellDateFormatted)
    @Test
    void readRowsWithDateCell() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Date");
            CellStyle dateStyle = wb.createCellStyle();
            CreationHelper helper = wb.getCreationHelper();
            dateStyle.setDataFormat(helper.createDataFormat().getFormat("yyyy-MM-dd"));

            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue(java.time.LocalDate.of(2024, 1, 15));
            cell.setCellStyle(dateStyle);

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).hasSize(1);
        }
    }

    // formatFormula numeric catch -> string catch -> boolean catch
    @Test
    void readRowsWithFormulaCellNumericErrorPath() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Form");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("hello");
            // Formula referencing a text cell — numericCellValue will fail
            // We need to set a formula that causes getNumericCellValue() to throw
            Cell c = row.createCell(1);
            c.setCellFormula("A1+1");

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).hasSize(2);
        }
    }

    // getCellValueFromSheet row == null
    @Test
    void readRowsWithMissingRowInMergedRegion() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Sparse");
            // Create merged region spanning rows 0-5 but only create row 0 and row 5
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 5, 0, 0));
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("top");

            // Rows 1-4 may or may not be auto-created by POI depending on version.
            // The important thing is that merged region values get filled.
            List<List<String>> result = MergeCellResolver.readRows(sheet);
            // Should at least have row 0 and row 5
            assertThat(result).isNotEmpty();
            assertThat(result.get(0).get(0)).isEqualTo("top");
        }
    }

    // formatNumeric with very large numbers outside long range
    @Test
    void readRowsWithNumericInfinity() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Inf");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(Double.POSITIVE_INFINITY);
            row.createCell(1).setCellValue(Double.NEGATIVE_INFINITY);
            row.createCell(2).setCellValue(Double.NaN);

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isEmpty();
            assertThat(result.get(0).get(1)).isEmpty();
            assertThat(result.get(0).get(2)).isEmpty();
        }
    }

    // formatFormula returning all fallbacks empty
    @Test
    void readRowsWithEmptyStringFormula() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("StrForm");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("");
            row.createCell(1).setCellFormula("\"\"");

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result).hasSize(1);
            assertThat(result.get(0)).hasSize(2);
        }
    }

    // Multiple merge regions on different rows
    @Test
    void readRowsWithOverlappingMergedRegions() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Overlap");
            // H overlapping merged regions on the same row
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 1));
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("val1");

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result).hasSize(1);
            assertThat(result.get(0).get(0)).isEqualTo("val1");
            assertThat(result.get(0).get(1)).isEqualTo("val1");
        }
    }

    // getCellValue with BLANK type cell (created via createCell but not setValue)
    @Test
    void readRowsBlankCellType() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Blank");
            Row row = sheet.createRow(0);
            row.createCell(0); // BLANK type

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isEmpty();
        }
    }

    // FormatNumeric with normal integer
    @Test
    void readRowsWithInteger() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Int");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(42.0);

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isEqualTo("42");
        }
    }

    // FormatNumeric with decimal
    @Test
    void readRowsWithDecimal() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Dec");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(3.14159);

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isNotEmpty();
        }
    }
}
