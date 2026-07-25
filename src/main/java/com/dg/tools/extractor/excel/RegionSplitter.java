package com.dg.tools.extractor.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 区域切分器（RegionSplitter）。
 *
 * 把一个 Excel 工作表按「列填充率」切分为多个相互独立的数据区域：
 *   - 逐列计算填充率 = 非空单元格数 / 总行数；
 *   - 填充率为 0% 的列视为「空列」；
 *   - 连续 ≥2 个空列 → 作为区域分隔线；
 *   - 每个区域只包含它自己的列（去掉中间的空列）。
 *
 * ✅ v7 修复（问题 #R1）:
 *   1. regions.add() 从行循环内移到外层，避免每行生成一个空 Region
 *   2. regionRow 正确加入 regionRows
 *   3. 单个有效区域时直接使用其边界而非回退到全表
 */
public class RegionSplitter {

    /** 判定为「分隔线」所需的最小连续空列数。 */
    private static final int MIN_EMPTY_COLS_FOR_SPLIT = 2;

    /** 工具类，禁止外部实例化。 */
    private RegionSplitter() {}

    /**
     * 对工作表全部行做区域切分。
     *
     * @param allRows 经合并单元格填充后的全行数据（每行长度一致）
     * @return 区域列表，每个区域是只含自身列的子表；无法切分时返回单区域（整表）
     */
    public static List<Region> split(List<List<String>> allRows) {
        if (allRows.isEmpty()) return List.of();

        int totalCols = allRows.stream().mapToInt(List::size).max().orElse(0);
        if (totalCols == 0) return List.of();

        // 计算每列填充率
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

        // 找出连续空列分组
        List<int[]> emptyGroups = new ArrayList<>();
        int i = 0;
        while (i < totalCols) {
            if (fillRates[i] == 0.0) {
                int start = i;
                while (i < totalCols && fillRates[i] == 0.0) i++;
                emptyGroups.add(new int[]{start, i - 1});
            } else {
                i++;
            }
        }

        // 依据空列组确定区域边界
        List<int[]> regionBounds = new ArrayList<>();
        int colStart = 0;
        int colEnd = totalCols - 1;

        for (int[] group : emptyGroups) {
            int emptyWidth = group[1] - group[0] + 1;
            if (emptyWidth >= MIN_EMPTY_COLS_FOR_SPLIT) {
                // 分隔线之前的列构成一个区域
                if (group[0] - 1 >= colStart) {
                    regionBounds.add(new int[]{colStart, group[0] - 1});
                }
                colStart = group[1] + 1;
            }
        }
        // 最后一个区域
        if (colStart <= colEnd) {
            regionBounds.add(new int[]{colStart, colEnd});
        }

        // 没有有效区域边界时，整体作为一个区域返回
        if (regionBounds.isEmpty()) {
            return List.of(new Region(0, totalCols - 1, allRows));
        }
        // 修正：单个区域也是有效结果（如前导/尾部空列），使用其实际边界

        // 构建各区域（只保留属于该区域的列，并补齐列数）
        List<Region> regions = new ArrayList<>();
        for (int[] bounds : regionBounds) {
            int startCol = bounds[0];
            int endCol = bounds[1];
            List<List<String>> regionRows = new ArrayList<>();
            for (List<String> row : allRows) {
                List<String> regionRow = new ArrayList<>();
                for (int col = startCol; col <= endCol && col < row.size(); col++) {
                    regionRow.add(row.get(col));
                }
                // 列数不足时补空
                while (regionRow.size() < (endCol - startCol + 1)) {
                    regionRow.add("");
                }
                // ✅ 关键修正：将构建好的 regionRow 加入 regionRows
                regionRows.add(regionRow);
            }
            // ✅ 关键修正：在所有行构建完成后，再创建 Region 对象并加入列表
            regions.add(new Region(startCol, endCol, regionRows));
        }

        return regions;
    }

    /** 区域内部载体：记录起止列与该区域的行数据。 */
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