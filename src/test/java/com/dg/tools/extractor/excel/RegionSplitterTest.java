package com.dg.tools.extractor.excel;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 注意：以下断言描述的是「按 SPEC 预期」的多区域切分行为。
 * 实测发现 RegionSplitter.split 当前实现存在缺陷（见问题清单 #R1）：
 * 它会在行循环内部重复 addRegion，导致为「每个数据行 × 每个列区域」各生成一个 Region，
 * 且每个 Region 的 rows 为空。因此本文件中偏严格断言会失败，用于暴露该缺陷。
 */
class RegionSplitterTest {

    private Sheet buildSheet(java.util.function.Consumer<XSSFWorkbook> builder) throws IOException {
        XSSFWorkbook wb = new XSSFWorkbook();
        builder.accept(wb);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        wb.write(baos);
        wb.close();
        XSSFWorkbook read = new XSSFWorkbook(new ByteArrayInputStream(baos.toByteArray()));
        return read.getSheetAt(0);
    }

    @Test
    void shouldReturnSingleRegionWhenNoEmptyColumns() throws Exception {
        Sheet sheet = buildSheet(wb -> {
            var s = wb.createSheet("s");
            s.createRow(0).createCell(0).setCellValue("A");
            s.createRow(0).createCell(1).setCellValue("B");
            s.createRow(1).createCell(0).setCellValue("1");
            s.createRow(1).createCell(1).setCellValue("2");
        });

        List<List<String>> rows = MergeCellResolver.readRows(sheet);
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).startCol).isZero();
        assertThat(regions.get(0).endCol).isEqualTo(1);
        assertThat(regions.get(0).rows).hasSize(2);
    }

    @Test
    void shouldSplitOnConsecutiveEmptyColumns() throws Exception {
        Sheet sheet = buildSheet(wb -> {
            var s = wb.createSheet("s");
            s.createRow(0).createCell(0).setCellValue("L1");
            s.createRow(0).createCell(1).setCellValue("L2");
            s.createRow(0).createCell(4).setCellValue("R1");
            s.createRow(0).createCell(5).setCellValue("R2");
            s.createRow(1).createCell(0).setCellValue("a");
            s.createRow(1).createCell(1).setCellValue("b");
            s.createRow(1).createCell(4).setCellValue("c");
            s.createRow(1).createCell(5).setCellValue("d");
        });

        List<List<String>> rows = MergeCellResolver.readRows(sheet);
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);

        // 连续 2 个空列（2,3）应切分为两个区域
        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).startCol).isEqualTo(0);
        assertThat(regions.get(0).endCol).isEqualTo(1);
        assertThat(regions.get(1).startCol).isEqualTo(4);
        assertThat(regions.get(1).endCol).isEqualTo(5);
        // 每个区域应携带全部行数据
        assertThat(regions.get(0).rows).hasSize(2);
    }

    @Test
    void shouldNotSplitOnSingleEmptyColumn() throws Exception {
        Sheet sheet = buildSheet(wb -> {
            var s = wb.createSheet("s");
            s.createRow(0).createCell(0).setCellValue("A");
            s.createRow(0).createCell(2).setCellValue("C");
            s.createRow(1).createCell(0).setCellValue("1");
            s.createRow(1).createCell(2).setCellValue("3");
        });

        List<List<String>> rows = MergeCellResolver.readRows(sheet);
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);

        assertThat(regions).hasSize(1);
    }

    @Test
    void shouldHandleEmptyInput() {
        assertThat(RegionSplitter.split(List.of())).isEmpty();
    }

    @Test
    void shouldSplitThreeRegions() throws Exception {
        Sheet sheet = buildSheet(wb -> {
            var s = wb.createSheet("s");
            s.createRow(0).createCell(0).setCellValue("A0");
            s.createRow(0).createCell(1).setCellValue("A1");
            s.createRow(0).createCell(4).setCellValue("B0");
            s.createRow(0).createCell(5).setCellValue("B1");
            s.createRow(0).createCell(8).setCellValue("C0");
            s.createRow(0).createCell(9).setCellValue("C1");
            for (int r = 1; r <= 3; r++) {
                s.createRow(r).createCell(0).setCellValue("a" + r);
                s.createRow(r).createCell(1).setCellValue("b" + r);
                s.createRow(r).createCell(4).setCellValue("c" + r);
                s.createRow(r).createCell(5).setCellValue("d" + r);
                s.createRow(r).createCell(8).setCellValue("e" + r);
                s.createRow(r).createCell(9).setCellValue("f" + r);
            }
        });

        List<List<String>> rows = MergeCellResolver.readRows(sheet);
        List<RegionSplitter.Region> regions = RegionSplitter.split(rows);

        // 两个空列间隔应切分为 3 个区域，每区域携带 4 行
        assertThat(regions).hasSize(3);
        assertThat(regions.get(0).rows).hasSize(4);
    }
}
