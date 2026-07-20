package com.dg.tools.extractor.excel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * 边界场景的预期行为测试。与 RegionSplitterTest 相同，当前实现存在缺陷
 * （区域在行循环内重复生成、rows 为空），因此部分断言会失败，用于暴露问题 #R1。
 */
class RegionSplitterBoundaryTest {

    private List<List<String>> withColumnFill(List<Double> fillRates) {
        int rows = 10;
        int cols = fillRates.size();
        List<List<String>> data = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) {
                double fr = fillRates.get(c);
                row.add((r < (int) (rows * fr)) ? "x" : "");
            }
            data.add(row);
        }
        return data;
    }

    @Test
    void shouldReturnSingleRegionWhenOnlyOneColumn() {
        assertThat(RegionSplitter.split(filledRect(3, 1))).hasSize(1);
    }

    @Test
    void shouldSplitWhenLeadingEmptyColumns() {
        List<List<String>> data = withColumnFill(List.of(0.0, 0.0, 1.0, 1.0));
        List<RegionSplitter.Region> regions = RegionSplitter.split(data);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).startCol).isEqualTo(2);
        assertThat(regions.get(0).rows).hasSize(10);
    }

    @Test
    void shouldSplitWhenTrailingEmptyColumns() {
        List<List<String>> data = withColumnFill(List.of(1.0, 1.0, 0.0, 0.0));
        List<RegionSplitter.Region> regions = RegionSplitter.split(data);
        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).endCol).isEqualTo(1);
        assertThat(regions.get(0).rows).hasSize(10);
    }

    @Test
    void shouldNotSplitWhenEmptyColumnsAreSingle() {
        List<List<String>> data = withColumnFill(List.of(1.0, 0.0, 1.0));
        assertThat(RegionSplitter.split(data)).hasSize(1);
    }

    @Test
    void shouldProduceThreeRegionsWithTwoGaps() {
        List<List<String>> data = withColumnFill(List.of(1.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 1.0, 1.0));
        List<RegionSplitter.Region> regions = RegionSplitter.split(data);
        assertThat(regions).hasSize(3);
        for (var rg : regions) assertThat(rg.rows).hasSize(10);
    }

    @Test
    void regionShouldContainOnlyItsColumns() {
        List<List<String>> data = withColumnFill(List.of(1.0, 1.0, 0.0, 0.0, 1.0, 1.0));
        List<RegionSplitter.Region> regions = RegionSplitter.split(data);
        RegionSplitter.Region second = regions.get(1);
        assertThat(second.startCol).isEqualTo(4);
        assertThat(second.endCol).isEqualTo(5);
        assertThat(second.rows.get(0)).hasSize(2);
    }

    private List<List<String>> filledRect(int rows, int cols) {
        List<List<String>> data = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            List<String> row = new ArrayList<>();
            for (int c = 0; c < cols; c++) row.add("v");
            data.add(row);
        }
        return data;
    }
}
