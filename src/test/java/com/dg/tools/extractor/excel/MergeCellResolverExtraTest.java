package com.dg.tools.extractor.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class MergeCellResolverExtraTest {

    @Test
    void readRowsEmptySheet() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Empty");
            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows).isEmpty();
        }
    }

    @Test
    void readRowsSimpleData() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Data");
            sheet.createRow(0).createCell(0).setCellValue("a");
            sheet.getRow(0).createCell(1).setCellValue("b");
            sheet.createRow(1).createCell(0).setCellValue("c");

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows).hasSize(2);
            assertThat(rows.get(0)).containsExactly("a", "b");
            assertThat(rows.get(1)).containsExactly("c", "");
        }
    }

    @Test
    void readRowsWithMergedRegion() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Merged");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("header");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 1, 0, 0));

            Row r1 = sheet.createRow(1);
            r1.createCell(1).setCellValue("data");

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(1).get(0)).isEqualTo("header");
        }
    }

    @Test
    void readRowsWithNumericCell() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Nums");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(42.0);
            row.createCell(1).setCellValue(3.14);
            row.createCell(2).setCellValue(1000L);

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(0).get(0)).isEqualTo("42");
            assertThat(rows.get(0).get(1)).isEqualTo("3.14");
        }
    }

    @Test
    void readRowsWithBooleanCell() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Bools");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(true);
            row.createCell(1).setCellValue(false);

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(0).get(0)).isEqualTo("true");
            assertThat(rows.get(0).get(1)).isEqualTo("false");
        }
    }

    @Test
    void readRowsWithFormulaCell() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Formulas");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(10);
            row.createCell(1).setCellValue(20);
            row.createCell(2).setCellFormula("A1+B1");

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(0).get(2)).isEqualTo("30.0");
        }
    }

    @Test
    void getCellValueNullCell() {
        assertThat(MergeCellResolver.getCellValue(null)).isEmpty();
    }

    @Test
    void readRowsNaNCell() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("NaN");
            Row row = sheet.createRow(0);
            row.createCell(0).setCellValue(Double.NaN);
            row.createCell(1).setCellValue(Double.POSITIVE_INFINITY);

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(0).get(0)).isEmpty();
            assertThat(rows.get(0).get(1)).isEmpty();
        }
    }

    @Test
    void readRowsMergedRegionExtendsBeyondData() throws Exception {
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("WideMerge");
            Row r0 = sheet.createRow(0);
            r0.createCell(0).setCellValue("wide");
            sheet.addMergedRegion(new org.apache.poi.ss.util.CellRangeAddress(0, 0, 0, 5));

            List<List<String>> rows = MergeCellResolver.readRows(sheet);
            assertThat(rows.get(0)).hasSize(6);
            assertThat(rows.get(0).get(0)).isEqualTo("wide");
            assertThat(rows.get(0).get(5)).isEqualTo("wide");
        }
    }
}
