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
import java.util.List;
import java.util.StringJoiner;

/**
 * Excel 处理器（ExcelHandler）。
 *
 * 基于 Apache POI 解析 .xlsx / .xls 表格文件，特点：
 *   - 合并单元格填充：借助 {@link MergeCellResolver} 把合并区域的值向四周铺满；
 *   - 多区域切分：借助 {@link RegionSplitter} 按「连续空列」把宽表切成若干独立区域；
 *   - 大小表分流：小表（≤50 行且 ≤10 列）直接渲染为 Markdown；
 *                  大表（超出阈值）仅写 data_ref 引用 + 完整 CSV 到 data/，避免正文膨胀；
 *   - CSV 切片：大表行数 >500 时由 {@link com.dg.tools.extractor.excel.CsvSlicer} 切分为 100 行/块。
 *
 * 对应 SPEC §9 路由表：.xlsx/.xls → ExcelHandler。
 */
@Component
@Slf4j
public class ExcelHandler extends AbstractHandler {

    private final int largeTableThreshold;
    private final int columnThreshold;

    public ExcelHandler() {
        this.largeTableThreshold = 50;
        this.columnThreshold = 10;
    }

    @Autowired
    public ExcelHandler(
            @Value("${extractor.excel.large-table-threshold:50}") int largeTableThreshold,
            @Value("${extractor.excel.column-threshold:10}") int columnThreshold) {
        this.largeTableThreshold = largeTableThreshold;
        this.columnThreshold = columnThreshold;
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") || lower.endsWith(".xls");
    }

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

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        String fileType = detectFileType(fileName);
        ExtractionResult result = ExtractionResult.of(fileType, fileName);
        int position = 0;

        try (Workbook wb = WorkbookFactory.create(is)) {
            for (int s = 0; s < wb.getNumberOfSheets(); s++) {
                Sheet sheet = wb.getSheetAt(s);
                String sheetName = sheet.getSheetName();

                List<List<String>> allRows = MergeCellResolver.readRows(sheet);
                if (allRows.isEmpty()) continue;

                List<Region> regions = RegionSplitter.split(allRows);
                for (int ri = 0; ri < regions.size(); ri++) {
                    Region region = regions.get(ri);
                    List<List<String>> regionRows = region.rows;
                    if (regionRows.isEmpty()) continue;

                    String label = regions.size() > 1
                            ? "[Sheet: " + sheetName + " / Region " + (ri + 1) + "]"
                            : "[Sheet: " + sheetName + "]";
                    result.addElement(new Element(position++, "sheet_header", label));

                    int headerIdx = findHeaderRowIndex(regionRows);
                    List<String> headerRow = regionRows.get(headerIdx);
                    List<List<String>> tableRows = regionRows.subList(headerIdx, regionRows.size());

                    if (tableRows.size() <= largeTableThreshold && headerRow.size() <= columnThreshold) {
                        StringBuilder md = new StringBuilder();
                        for (int r = 0; r < tableRows.size(); r++) {
                            md.append("| ").append(String.join(" | ", tableRows.get(r))).append(" |\n");
                            if (r == 0) {
                                md.append("| ").append(tableRows.get(r).stream()
                                        .map(c -> "---")
                                        .collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
                            }
                        }
                        result.addElement(new Element(position++, "table", md.toString().trim()));
                    } else {
                        StringJoiner schema = new StringJoiner(" | ");
                        for (String cell : headerRow) {
                            schema.add(cell != null ? cell.trim() : "");
                        }

                        StringBuilder preview = new StringBuilder();
                        preview.append("Columns: ").append(schema).append("\n");
                        int previewEnd = Math.min(1 + 2, tableRows.size());
                        for (int r = 1; r < previewEnd; r++) {
                            preview.append("Row ").append(r).append(": ");
                            StringJoiner rowStr = new StringJoiner(", ");
                            for (String cell : tableRows.get(r)) {
                                if (cell != null && !cell.trim().isEmpty()) {
                                    rowStr.add(cell.trim());
                                }
                            }
                            preview.append(rowStr).append("\n");
                        }

                        LargeTableInfo lti = new LargeTableInfo(
                                sheetName, position++, schema.toString(),
                                preview.toString().trim(), tableRows.size(), tableRows
                        );
                        result.addLargeTable(lti);
                    }
                }
            }

            extractImages(wb, result, position);

        } catch (Exception e) {
            log.error("Failed to parse excel: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

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
     * 多列表：至少 2 个非空单元格视为表头（排除标题/说明行）。
     * 单列表：至少 1 个非空单元格即为表头。
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

    // ==================== 工具方法 ====================

    private static String detectFileType(String fileName) {
        if (fileName != null && fileName.toLowerCase().endsWith(".xls")) return "xls";
        return "xlsx";
    }

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
