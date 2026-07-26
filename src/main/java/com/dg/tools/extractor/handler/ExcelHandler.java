package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.excel.MergeCellResolver;
import com.dg.tools.extractor.excel.RegionSplitter;
import com.dg.tools.extractor.excel.RegionSplitter.Region;
import com.dg.tools.extractor.model.*;
import org.apache.poi.ss.usermodel.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * Excel 处理器（.xlsx / .xls）。
 *
 * 基于 Apache POI 解析 Excel 表格文件，核心特性：
 * <ul>
 *   <li>合并单元格填充：{@link MergeCellResolver} 将合并区域值向下方+右方铺满</li>
 *   <li>多区域切分：{@link RegionSplitter} 按连续空列≥2 切分为独立数据区域</li>
 *   <li>大小表分流：小表（≤100行且≤10列）→ Markdown 内联；大表 → data_ref + CSV 分离到 data/</li>
 *   <li>CSV 切片：大表行数 >500 时由 CsvSlicer 切为 100行/块</li>
 * </ul>
 *
 * <p>阈值可通过 application.yml 配置项调整：
 * {@code extractor.excel.large-table-threshold: 50}
 * {@code extractor.excel.column-threshold: 10}</p>
 *
 * @see MergeCellResolver
 * @see RegionSplitter
 */
@Component
@Slf4j
public class ExcelHandler extends AbstractHandler {

    /** 大表判定阈值：最大行数（超出则走 data_ref 路径）。默认 100 行。 */
    private final int largeTableThreshold;

    /** 大表判定阈值：最大列数（超出则走 data_ref 路径）。默认 10 列。 */
    private final int columnThreshold;

    /** 无参构造函数：使用默认阈值常量。被 Spring 忽略，仅作安全回退。 */
    public ExcelHandler() {
        this.largeTableThreshold = 100;
        this.columnThreshold = 10;
    }

    /**
     * 通过 Spring 配置注入阈值。
     * @param largeTableThreshold 大表最大行数
     * @param columnThreshold     大表最大列数
     */
    @Autowired
    public ExcelHandler(
            @Value("${extractor.excel.large-table-threshold:100}") int largeTableThreshold,
            @Value("${extractor.excel.column-threshold:10}") int columnThreshold) {
        this.largeTableThreshold = largeTableThreshold;
        this.columnThreshold = columnThreshold;
    }

    /** 判断当前文件名是否为 .xlsx 或 .xls 扩展名。 */
    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

    // ==================== 阶段 1：UNPACK（仅图片） ====================

    /**
     * Phase 1 拆包：仅提取所有工作表中的图片，不解析任何表格数据。
     * 使用 WorkbookFactory.create(is) 同时兼容 .xlsx 和 .xls。
     */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        String fileType = detectFileType(fileName);
        ExtractionResult unpacked = ExtractionResult.of(fileType, fileName);
        int pos = 0;

        try (Workbook wb = WorkbookFactory.create(is)) {
            List<? extends PictureData> pictures = wb.getAllPictures();
            for (PictureData pic : pictures) {
                String format = detectImageFormat(pic.getMimeType());
                String imgName = "excel_image_" + pos + "." + format;
                unpacked.addImage(new ImageFile(imgName, pos, pic.getData(), format));
                pos++;
            }
        } catch (Exception e) {
            log.error("Failed to unpack excel: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

    // ==================== 阶段 2：EXTRACT（完整解析） ====================

    /**
     * Phase 2 完整解析：逐 Sheet → 逐 Region（按空列切分）→ 渲染表格或生成 data_ref。
     * <p>处理流程：
     * <ol>
     *   <li>遍历每个 Sheet</li>
     *   <li>MergeCellResolver.readRows(sheet) → 填充合并单元格，得到矩形化二维列表</li>
     *   <li>RegionSplitter.split(allRows) → 按连续空列≥2切分为独立区域</li>
     *   <li>每个区域找到表头行 → 判断大小表 → 小表渲染 Markdown，大表写入 CSV</li>
     * </ol>
     */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        String fileType = detectFileType(fileName);
        ExtractionResult result = ExtractionResult.of(fileType, fileName);
        int position = 0;

        try (Workbook wb = WorkbookFactory.create(is)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                // 合并单元格填充 + 列数归一化 → 矩形化二维列表（含 filled / compact 双网格）
                var sheetData = MergeCellResolver.readRowsWithMeta(sheet);
                List<List<String>> allRows = sheetData.filled();
                if (allRows.isEmpty()) continue;

                // 按连续空列 ≥2 切分为多个独立数据区域（使用 filled 网格保证列填充率准确）
                List<Region> regions = RegionSplitter.split(allRows);
                for (int ri = 0; ri < regions.size(); ri++) {
                    Region region = regions.get(ri);
                    if (region.rows.isEmpty()) continue;

                    // 输出 Sheet/Region 标签
                    String label = regions.size() > 1
                            ? "[Sheet: " + sheetName + " / Region " + (ri + 1) + "]"
                            : "[Sheet: " + sheetName + "]";
                    result.addElement(new Element(position++, "sheet_header", label));

                    // 从 compact 网格中提取对应区域（不包含合并填充值，用于 Markdown + 阈值判断）
                    List<List<String>> compactRows = extractRegionColumns(sheetData.compact(), region);
                    int headerIdx = findHeaderRowIndex(compactRows);

                    // 输出 title 行（header 之前的合并/标题行，用 compact 网格避免合并填充重复）
                    for (int r = 0; r < headerIdx; r++) {
                        String titleText = compactRows.get(r).stream()
                                .filter(c -> c != null && !c.trim().isEmpty())
                                .collect(Collectors.joining(" "));
                        if (!titleText.trim().isEmpty()) {
                            result.addElement(new Element(position++, "paragraph", titleText.trim()));
                        }
                    }

                    // 从 compact 网格中取表头及数据行
                    List<String> compactHeader = compactRows.get(headerIdx);
                    List<List<String>> compactTableRows = compactRows.subList(headerIdx, compactRows.size());

                    // 从 filled 网格中提取对应区域（含合并填充值，用于 CSV 输出）
                    List<List<String>> filledRows = extractRegionColumns(sheetData.filled(), region);
                    List<List<String>> filledTableRows = filledRows.subList(headerIdx, filledRows.size());

                    // ========== 大小表分流（阈值判断使用 compact 网格避免虚高） ==========
                    if (compactTableRows.size() <= largeTableThreshold && compactHeader.size() <= columnThreshold) {
                        // 小表：使用 compact 网格渲染 Markdown 表格（无合并填充重复）
                        StringBuilder md = new StringBuilder();
                        for (int r = 0; r < compactTableRows.size(); r++) {
                            md.append("| ").append(String.join(" | ", compactTableRows.get(r))).append(" |\n");
                            if (r == 0) {
                                md.append("| ").append(compactTableRows.get(r).stream()
                                        .map(c -> "---")
                                        .collect(Collectors.joining(" | "))).append(" |\n");
                            }
                        }
                        result.addElement(new Element(position++, "table", md.toString().trim()));
                    } else {
                        // 大表 → data_ref + CSV（使用 filled 网格保证数据完整性）
                        StringJoiner schema = new StringJoiner(" | ");
                        for (String cell : compactHeader) {
                            schema.add(cell != null ? cell.trim() : "");
                        }

                        StringBuilder preview = new StringBuilder();
                        preview.append("Columns: ").append(schema).append("\n");
                        int previewEnd = Math.min(1 + 2, filledTableRows.size());
                        for (int r = 1; r < previewEnd; r++) {
                            preview.append("Row ").append(r).append(": ");
                            StringJoiner rowStr = new StringJoiner(", ");
                            for (String cell : filledTableRows.get(r)) {
                                if (cell != null && !cell.trim().isEmpty()) {
                                    rowStr.add(cell.trim());
                                }
                            }
                            preview.append(rowStr).append("\n");
                        }

                        LargeTableInfo lti = new LargeTableInfo(
                                sheetName, position++, schema.toString(),
                                preview.toString().trim(), filledTableRows.size(), filledTableRows
                        );
                        result.addLargeTable(lti);
                    }
                }
            }

            // 最后提取 Sheet 中的图片
            extractImages(wb, result, position);

        } catch (Exception e) {
            log.error("Failed to parse excel: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    /**
     * 从 Workbook 中提取所有图片，生成 TYPE: image Element。
     */
    private void extractImages(Workbook wb, ExtractionResult result, int startPosition) {
        try {
            List<? extends PictureData> pictures = wb.getAllPictures();
            int pos = startPosition;
            for (PictureData pic : pictures) {
                String format = detectImageFormat(pic.getMimeType());
                String imgName = "excel_image_" + pos + "." + format;
                result.addImage(new ImageFile(imgName, pos, pic.getData(), format));
                pos++;
            }
        } catch (Exception e) {
            result.addError("images: " + e.getMessage());
        }
    }

    /**
     * 智能定位表头行索引。
     * <p>判定规则：
     * <ul>
     *   <li>多列表：第一行至少有 2 个非空单元格 → 视为表头</li>
     *   <li>单列表：第一行至少有 1 个非空单元格 → 即为表头</li>
     *   <li>全为空 → 返回 0（第一行作为表头）</li>
     * </ul>
     * 此规则排除标题/说明行等干扰内容。
     */
    private int findHeaderRowIndex(List<List<String>> allRows) {
        if (allRows.isEmpty()) return 0;
        // 判断是否为单列表（所有行的最大列数 ≤1）
        boolean singleColumn = allRows.stream().allMatch(row -> row.size() <= 1);
        int threshold = singleColumn ? 1 : 2;
        for (int i = 0; i < allRows.size(); i++) {
            long nonEmpty = allRows.get(i).stream()
                    .filter(c -> c != null && !c.trim().isEmpty())
                    .count();
            if (nonEmpty >= threshold) return i;
        }
        return 0;
    }

    /**
     * 从源网格中提取 {@link Region} 对应的列子集。
     * RegionSplitter 从 filled 网格切分后，compact 网格需按相同列边界提取以保持行列对应。
     */
    private static List<List<String>> extractRegionColumns(List<List<String>> source, Region region) {
        List<List<String>> result = new ArrayList<>();
        for (List<String> row : source) {
            List<String> regionRow = new ArrayList<>();
            for (int col = region.startCol; col <= region.endCol; col++) {
                regionRow.add(col < row.size() ? row.get(col) : "");
            }
            result.add(regionRow);
        }
        return result;
    }

    // ==================== 工具方法 ====================

    /**
     * 根据文件名后缀判断文件类型标识。
     * @return "xls" 或 "xlsx"
     */
    private static String detectFileType(String fileName) {
        if (fileName != null && fileName.toLowerCase().endsWith(".xls")) return "xls";
        return "xlsx";
    }

    /**
     * 从 MIME 类型字符串提取图片格式标识。
     * 常见格式：png/jpeg/gif/bmp/tiff/emf/wmf/svg。
     * 未知格式走 default → 尝试斜杠后子串 → "bin"。
     */
    private static String detectImageFormat(String mimeType) {
        if (mimeType == null) return "bin";
        return switch (mimeType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/gif" -> "gif";
            case "image/bmp", "image/x-bmp" -> "bmp";
            case "image/tiff", "image/x-tiff" -> "tiff";
            case "image/x-emf" -> "emf";
            case "image/x-wmf" -> "wmf";
            case "image/svg+xml" -> "svg";
            default -> {
                int slash = mimeType.indexOf('/');
                yield slash > 0 ? mimeType.substring(slash + 1) : "bin";
            }
        };
    }
}
