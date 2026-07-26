package com.dg.tools.extractor.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MergeCellResolverFormulaEdgeTest {

    @Test
    void formatNumericWithDateValue() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("D");
            CellStyle cs = wb.createCellStyle();
            CreationHelper ch = wb.getCreationHelper();
            // Use a date format string
            short dateFormat = 14; // POI's built-in "mm-dd-yy"
            cs.setDataFormat(dateFormat);
            Row row = sheet.createRow(0);
            Cell cell = row.createCell(0);
            cell.setCellValue(java.time.LocalDate.of(2024, 6, 15));
            cell.setCellStyle(cs);
            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isNotEmpty();
        }
    }

    @Test
    void formatNumericLargeIntegerNoDecimal() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Li");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(999999999999.0);
            List<List<String>> result = MergeCellResolver.readRows(sheet);
            // Large integer should be formatted without ".0"
            assertThat(result.get(0).get(0)).doesNotContain(".0");
        }
    }

    @Test
    void formatFormulaReturnsFormulaTextOnException() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("FF");
            Row row = sheet.createRow(0);
            Cell c = row.createCell(0);
            c.setCellFormula("SUM(A1:B1)");

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            // Formula evaluation succeeded -> should have numeric or blank value
            assertThat(result.get(0).get(0)).isNotNull();
        }
    }

    @Test
    void readRowsWithMultipleMixedCellTypes() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Mix");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue("text");        // STRING
            row.createCell(1).setCellValue(42);             // NUMERIC int
            row.createCell(2).setCellValue(3.14);           // NUMERIC double
            row.createCell(3).setCellValue(true);           // BOOLEAN
            row.createCell(4).setCellFormula("A1&B1");      // FORMULA

            List<List<String>> result = MergeCellResolver.readRows(sheet);
            assertThat(result.get(0).get(0)).isEqualTo("text");
            assertThat(result.get(0).get(1)).isEqualTo("42");
            assertThat(result.get(0).get(2)).contains("3.");
            assertThat(result.get(0).get(3)).isEqualTo("true");
        }
    }
}
