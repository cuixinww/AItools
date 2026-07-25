package com.dg.tools.extractor.excel;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 合并单元格填充器（MergeCellResolver）。
 *
 * 作用：把 Excel 中合并区域（merged region）的值向「下方 + 右方」铺满，
 * 使每个本应空白的合并单元格都获得与合并源相同的值。
 * 处理后，每一行都被归一化为相同的列数，得到矩形化的二维数据，
 * 便于后续的多区域切分与表格渲染。
 */
@Slf4j
public class MergeCellResolver {

    /** 工具类，禁止外部实例化。 */
    private MergeCellResolver() {}

    /**
     * 读取一个 Sheet 的全部行，填充合并单元格值并做列数归一化。
     *
     * @param sheet 待处理的 Sheet
     * @return 完全填充后的矩形行数据（每行长度一致）
     */
    public static List<List<String>> readRows(Sheet sheet) {
        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

        // 第一遍：读取所有行，记录最大列数
        List<RowData> rows = new ArrayList<>();
        int maxCols = 0;

        for (Row row : sheet) {
            List<String> rowData = new ArrayList<>();
            int lastCol = row.getLastCellNum();
            for (int c = 0; c < lastCol; c++) {
                rowData.add(getCellValue(row.getCell(c)));
            }
            rows.add(new RowData(row.getRowNum(), rowData));
            maxCols = Math.max(maxCols, lastCol);
        }

        // 合并区域可能向右延伸到超过已有数据的列，因此也要纳入 maxCols 计算
        for (CellRangeAddress r : mergedRegions) {
            maxCols = Math.max(maxCols, r.getLastColumn() + 1);
        }

        // 预缓存每个合并区域的源值（区域左上角单元格的值），每个区域只读取一次
        Map<CellRangeAddress, String> regionValues = new HashMap<>();
        for (CellRangeAddress region : mergedRegions) {
            String sv = getCellValueFromSheet(sheet, region.getFirstRow(), region.getFirstColumn());
            if (sv != null && !sv.isEmpty()) {
                regionValues.put(region, sv);
            }
        }

        // 第二遍：补齐列数，并把合并区域的值铺满到空白单元格
        for (RowData rd : rows) {
            // 补齐到统一宽度
            while (rd.data.size() < maxCols) {
                rd.data.add("");
            }

            for (CellRangeAddress region : mergedRegions) {
                // 仅处理与本行相交的合并区域
                if (rd.rowNum < region.getFirstRow() || rd.rowNum > region.getLastRow()) {
                    continue;
                }
                String sourceValue = regionValues.get(region);
                if (sourceValue == null) continue;

                for (int col = region.getFirstColumn(); col <= region.getLastColumn() && col < maxCols; col++) {
                    // 跳过合并源单元格本身（保留原值）
                    if (rd.rowNum == region.getFirstRow() && col == region.getFirstColumn()) continue;
                    String value = rd.data.get(col);
                    if (value.isEmpty()) {
                        rd.data.set(col, sourceValue);
                    }
                }
            }
        }

        List<List<String>> result = new ArrayList<>();
        for (RowData rd : rows) {
            result.add(rd.data);
        }
        return result;
    }

    // ==================== 单元格取值 ====================

    /** POI 的单元格格式化器（处理日期、自定义格式、避免科学计数法等）。 */
    private static final ThreadLocal<DataFormatter> FORMATTER = ThreadLocal.withInitial(DataFormatter::new);

    /** 从指定坐标读取单元格值（行不存在时返回空串）。 */
    private static String getCellValueFromSheet(Sheet sheet, int rowNum, int colNum) {
        Row row = sheet.getRow(rowNum);
        if (row == null) return "";
        return getCellValue(row.getCell(colNum));
    }

    /** 按单元格类型读取值并统一转为字符串。 */
    static String getCellValue(Cell cell) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatNumeric(cell);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formatFormula(cell);
            default -> "";
        };
    }

    /** 数值单元格格式化：日期用 DataFormatter；整数去小数；其余尽量不用科学计数法。 */
    private static String formatNumeric(Cell cell) {
        // 优先用 DataFormatter 处理日期、自定义格式，并避免科学计数法
        if (DateUtil.isCellDateFormatted(cell)) {
            try {
                return FORMATTER.get().formatCellValue(cell);
            } catch (Exception e) {
                log.warn("Failed to format date cell", e);
            }
        }
        // 整数范围内直接以 long 输出，避免 ".0"
        double v = cell.getNumericCellValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return "";
        if (v == (long) v && v >= Long.MIN_VALUE && v <= Long.MAX_VALUE) {
            return String.valueOf((long) v);
        }
        // 其余情况用 BigDecimal 风格格式化（DataFormatter 最稳妥）
        try {
            return FORMATTER.get().formatCellValue(cell);
        } catch (Exception e) {
            log.warn("Failed to format numeric cell, falling back to raw value", e);
            return String.valueOf(v);
        }
    }

    /** 公式单元格格式化：优先取缓存的数值结果，失败再依次尝试字符串 / 布尔 / 公式文本。 */
    private static String formatFormula(Cell cell) {
        try {
            return String.valueOf(cell.getNumericCellValue());
        } catch (Exception e1) {
            log.warn("Failed to read formula numeric result, trying string", e1);
        }
        try {
            return cell.getStringCellValue();
        } catch (Exception e2) {
            log.warn("Failed to read formula string result, trying boolean", e2);
        }
        try {
            return String.valueOf(cell.getBooleanCellValue());
        } catch (Exception e3) {
            log.warn("Failed to read formula boolean result, returning formula text", e3);
            return cell.getCellFormula();
        }
    }

    // ==================== 内部类型 ====================

    /** 行的内部载体：记录原始行号与解析出的单元格数据。 */
    private static class RowData {
        final int rowNum;
        final List<String> data;
        RowData(int rowNum, List<String> data) {
            this.rowNum = rowNum;
            this.data = data;
        }
    }
}
