package com.dg.tools.extractor.excel;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 合并单元格填充器。
 *
 * 把 Excel 中合并区域（merged region）的值向「下方 + 右方」铺满，
 * 使每个本应空白的合并单元格都获得与合并源相同的值。
 * 处理后，每一行都被归一化为相同的列数，得到矩形化的二维数据，
 * 便于后续 {@link RegionSplitter} 多区域切分与 Markdown 表格渲染。
 *
 * <p>性能优化：
 * <ul>
 *   <li>DataFormatter 和 FormulaEvaluator 在 readRows 生命周期内只创建一次并复用</li>
 *   <li>合并区域源值（左上角单元格的值）预缓存到 HashMap，避免重复读取</li>
 * </ul>
 *
 * @see ExcelHandler#doExtract
 * @see RegionSplitter
 */
@Slf4j
public class MergeCellResolver {

    /** 工具类，禁止外部实例化。 */
    private MergeCellResolver() {}

    /**
     * Sheet 数据容器，包含两个不同填充程度的网格。
     * <ul>
     *   <li>{@link #filled} — 合并区域值已向下方+右方铺满，用于 RegionSplitter 和 CSV 输出</li>
     *   <li>{@link #compact} — 仅做列数归一化（不填充合并区域），用于 Markdown 渲染和大小表阈值判断</li>
     * </ul>
     */
    public record SheetData(List<List<String>> filled, List<List<String>> compact) {}

    /**
     * 读取一个 Sheet 的全部行，返回填充合并单元格后的矩形数据。
     * 向下兼容，等价于调用 {@code readRowsWithMeta(sheet).filled()}。
     *
     * @param sheet 待处理的 Sheet
     * @return 完全填充后的矩形行数据（每行长度一致）
     */
    public static List<List<String>> readRows(Sheet sheet) {
        return readRowsWithMeta(sheet).filled();
    }

    /**
     * 读取一个 Sheet 的全部行，返回填充合并单元格后的矩形数据。
     * FormulaEvaluator 在整个 readRows 调用中只创建一次、复用，
     * 避免每个公式单元格都 new 一个 evaluator。
     *
     * <p>处理流程：
     * <ol>
     *   <li>第一遍：遍历所有行，读取已有单元格值，记录最大列数</li>
     *   <li>扫描合并区域，计算可能的最大列数（合并可能延伸到已有数据右侧）</li>
     *   <li>预缓存每个合并区域的源值（区域左上角单元格），避免重复读取</li>
     *   <li>第二遍：补齐每行列数（此时构造 compact 网格——不填充合并区域）</li>
     *   <li>第三遍：将合并区域的值铺满到空白单元格（构造 filled 网格——供 CSV 使用）</li>
     * </ol>
     *
     * @param sheet 待处理的 Sheet
     * @return 包含 filled 和 compact 双网格的 {@link SheetData}
     */
    public static SheetData readRowsWithMeta(Sheet sheet) {
        Objects.requireNonNull(sheet, "sheet must not be null");
        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

        // 每个 readRows 调用创建一次 DataFormatter 和 FormulaEvaluator，整个生命周期内复用
        DataFormatter formatter = new DataFormatter();
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        // ========== 第一遍：读取所有行，记录最大列数 ==========
        List<RowData> rows = new ArrayList<>();
        int maxCols = 0;

        for (Row row : sheet) {
            List<String> rowData = new ArrayList<>();
            int lastCol = row.getLastCellNum();
            for (int c = 0; c < lastCol; c++) {
                rowData.add(getCellValue(row.getCell(c), formatter, evaluator));
            }
            rows.add(new RowData(row.getRowNum(), rowData));
            maxCols = Math.max(maxCols, lastCol);
        }

        // 合并区域可能向右延伸到超过已有数据的列，因此也要纳入 maxCols 计算
        for (CellRangeAddress r : mergedRegions) {
            maxCols = Math.max(maxCols, r.getLastColumn() + 1);
        }

        // ========== 预缓存每个合并区域的源值（区域左上角单元格的值），每个区域只读取一次 ==========
        Map<CellRangeAddress, String> regionValues = new HashMap<>();
        for (CellRangeAddress region : mergedRegions) {
            String sv = getCellValueFromSheet(sheet, region.getFirstRow(), region.getFirstColumn(),
                    formatter, evaluator);
            if (sv != null && !sv.isEmpty()) {
                regionValues.put(region, sv);
            }
        }

        // ========== 第二遍：补齐列数（compact 网格——不填充合并区域） ==========
        for (RowData rd : rows) {
            while (rd.data.size() < maxCols) {
                rd.data.add("");
            }
        }

        // 保存 compact 网格（归一化但未填充合并区域的副本）
        List<List<String>> compact = new ArrayList<>();
        for (RowData rd : rows) {
            compact.add(new ArrayList<>(rd.data));
        }

        // ========== 第三遍：把合并区域的值铺满到空白单元格（filled 网格——供 CSV 输出使用） ==========
        for (RowData rd : rows) {
            for (CellRangeAddress region : mergedRegions) {
                if (rd.rowNum < region.getFirstRow() || rd.rowNum > region.getLastRow()) {
                    continue;
                }
                String sourceValue = regionValues.get(region);
                if (sourceValue == null) continue;

                for (int col = region.getFirstColumn(); col <= region.getLastColumn() && col < maxCols; col++) {
                    if (rd.rowNum == region.getFirstRow() && col == region.getFirstColumn()) continue;
                    if (rd.data.get(col).isEmpty()) {
                        rd.data.set(col, sourceValue);
                    }
                }
            }
        }

        List<List<String>> filled = new ArrayList<>();
        for (RowData rd : rows) {
            filled.add(rd.data);
        }
        return new SheetData(filled, compact);
    }

    // ==================== 单元格取值 ====================

    /**
     * 从指定坐标读取单元格值（行不存在时返回空串）。
     * 与直接 row.getCell() 不同，此方法允许 rowNum 指向不存在的行。
     */
    private static String getCellValueFromSheet(Sheet sheet, int rowNum, int colNum,
                                                  DataFormatter formatter, FormulaEvaluator evaluator) {
        Row row = sheet.getRow(rowNum);
        if (row == null) return "";
        return getCellValue(row.getCell(colNum), formatter, evaluator);
    }

    /**
     * 按单元格类型读取值并统一转为字符串（兼容旧调用方，内部不缓存 formatter/evaluator）。
     */
    static String getCellValue(Cell cell) {
        return getCellValue(cell, new DataFormatter(), null);
    }

    /**
     * 按单元格类型读取值并统一转为字符串。
     * STRING → 直接取字符串；NUMERIC → 格式化输出；BOOLEAN → "true"/"false"；
     * FORMULA → 先求值再格式化，失败则回退为公式文本；BLANK/ERROR → ""。
     */
    static String getCellValue(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) return "";
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> formatNumeric(cell, formatter);
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> formatFormula(cell, formatter, evaluator);
            default -> "";
        };
    }

    /**
     * 数值单元格格式化：日期优先用 DataFormatter 处理自定义格式；整数范围内转为 long 避免 ".0"；
     * 其余情况尝试 DataFormatter，失败回退原始 double 值。
     */
    private static String formatNumeric(Cell cell, DataFormatter formatter) {
        // 优先用 DataFormatter 处理日期、自定义格式，并避免科学计数法
        if (DateUtil.isCellDateFormatted(cell)) {
            try {
                return formatter.formatCellValue(cell);
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
            return formatter.formatCellValue(cell);
        } catch (Exception e) {
            log.warn("Failed to format numeric cell, falling back to raw value", e);
            return String.valueOf(v);
        }
    }

    /**
     * 公式单元格格式化：用缓存的 evaluator 计算真实结果，再根据计算结果的类型格式化；
     * 评估失败则回退为公式文本字符串（如 "=SUM(A1:A10)"）。
     */
    private static String formatFormula(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        try {
            if (evaluator == null) {
                // 兼容旧调用方（无 evaluator 时）
                evaluator = cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator();
            }
            CellValue value = evaluator.evaluate(cell);
            if (value == null) {
                return cell.getCellFormula();
            }
            return switch (value.getCellType()) {
                case NUMERIC -> String.valueOf(value.getNumberValue());
                case STRING -> value.getStringValue();
                case BOOLEAN -> String.valueOf(value.getBooleanValue());
                case BLANK -> "";
                case ERROR -> "";
                default -> cell.getCellFormula();
            };
        } catch (Exception e) {
            log.warn("Failed to evaluate formula cell, falling back to formula text", e);
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
