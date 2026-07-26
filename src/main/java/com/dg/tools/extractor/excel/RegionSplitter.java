package com.dg.tools.extractor.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域切分器。
 *
 * 把一个 Excel 工作表按「列填充率」切分为多个相互独立的数据区域：
 * <ul>
 *   <li>逐列计算填充率 = 非空单元格数 / 总行数</li>
 *   <li>填充率为 0% 的列视为「空列」</li>
 *   <li>连续 ≥2 个空列 → 作为区域分隔线</li>
 *   <li>每个区域只包含它自己的列（去掉中间的空列）</li>
 * </ul>
 *
 * <p>适用场景：一份 Excel 中同时包含多个独立表格（如统计表+明细表），
 * 它们之间用空列隔开。此工具自动识别并切分开，避免渲染出一个列数过宽、
 * 大部分为空白的巨型 Markdown 表格。</p>
 *
 * @see ExcelHandler#doExtract
 */
public class RegionSplitter {

    /** 判定为「分隔线」所需的最小连续空列数。连续 1 个空列不触发切分（可能只是单列表头留白）。 */
    private static final int MIN_EMPTY_COLS_FOR_SPLIT = 2;

    /** 工具类，禁止外部实例化。 */
    private RegionSplitter() {}

    /**
     * 对工作表全部行做区域切分。
     *
     * @param allRows 经 {@link MergeCellResolver#readRows} 合并单元格填充后的全行数据（每行长度一致）
     * @return 区域列表，每个区域是只含自身列的子表；无法切分时返回单区域（整表）；全部为空时返回空列表
     */
    public static List<Region> split(List<List<String>> allRows) {
        if (allRows.isEmpty()) return List.of();

        // 取所有行的最大列数作为总列数
        int totalCols = allRows.stream().mapToInt(List::size).max().orElse(0);
        if (totalCols == 0) return List.of();

        // ========== 计算每列填充率 ==========
        double[] fillRates = new double[totalCols];
        int totalRows = allRows.size();
        for (int col = 0; col < totalCols; col++) {
            int nonEmpty = 0;
            for (List<String> row : allRows) {
                if (col < row.size()) {
                    String val = row.get(col);
                    if (val != null && !val.trim().isEmpty()) {
                        nonEmpty++;
                    }
                }
            }
            fillRates[col] = (double) nonEmpty / totalRows;
        }

        // 判断是否全部列为空（无有效数据）
        boolean allEmpty = true;
        for (double rate : fillRates) {
            if (rate > 0.0) {
                allEmpty = false;
                break;
            }
        }
        if (allEmpty) {
            return List.of();
        }

        // ========== 找出连续空列分组 ==========
        List<int[]> emptyGroups = new ArrayList<>();
        int i = 0;
        while (i < totalCols) {
            if (fillRates[i] == 0.0) {
                int start = i;
                while (i < totalCols && fillRates[i] == 0.0) i++;
                // [start, end] 为一组连续空列的索引范围
                emptyGroups.add(new int[]{start, i - 1});
            } else {
                i++;
            }
        }

        // ========== 依据空列组确定区域边界 ==========
        List<int[]> regionBounds = new ArrayList<>();
        int colStart = 0;
        int colEnd = totalCols - 1;

        for (int[] group : emptyGroups) {
            int emptyWidth = group[1] - group[0] + 1;
            if (emptyWidth >= MIN_EMPTY_COLS_FOR_SPLIT) {
                // 该连续空列组宽度 ≥ 2，视为分隔线
                // 分隔线之前的列构成一个区域
                if (group[0] - 1 >= colStart) {
                    regionBounds.add(new int[]{colStart, group[0] - 1});
                }
                // 下一个区域的起点为当前空列组之后一列
                colStart = group[1] + 1;
            }
        }
        // 最后一个区域（从最后一个分隔线到末尾）
        if (colStart <= colEnd) {
            regionBounds.add(new int[]{colStart, colEnd});
        }

        // 没有有效区域边界时，整体作为一个区域返回
        if (regionBounds.isEmpty()) {
            return List.of(new Region(0, totalCols - 1, allRows));
        }

        // ========== 构建各区域（只保留属于该区域的列，并补齐列数） ==========
        List<Region> regions = new ArrayList<>();
        for (int[] bounds : regionBounds) {
            int startCol = bounds[0];
            int endCol = bounds[1];
            if (startCol > endCol) {
                continue;
            }
            List<List<String>> regionRows = new ArrayList<>();
            for (List<String> row : allRows) {
                List<String> regionRow = new ArrayList<>();
                for (int col = startCol; col <= endCol; col++) {
                    if (col < row.size()) {
                        regionRow.add(row.get(col));
                    } else {
                        regionRow.add("");
                    }
                }
                regionRows.add(regionRow);
            }
            regions.add(new Region(startCol, endCol, regionRows));
        }

        return regions;
    }

    /**
     * 区域内部载体：记录起止列与该区域的行数据。
     */
    public static class Region {
        public final int startCol;
        public final int endCol;
        public final List<List<String>> rows;

        public Region(int startCol, int endCol, List<List<String>> rows) {
            this.startCol = startCol;
            this.endCol = endCol;
            this.rows = rows;
        }
    }
}