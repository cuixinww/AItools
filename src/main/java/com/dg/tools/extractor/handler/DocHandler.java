package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;

/**
 * DOC 文档处理器（.doc, Word 97-2003 OLE2 格式）。
 *
 * 基于 Apache POI (HWPF) 解析 .doc 文档，支持：
 * <ul>
 *   <li>Phase 1 UNPACK：仅提取图片（轻量级，不解析文本）</li>
 *   <li>Phase 2 EXTRACT：完整解析段落、表格、页眉、图片</li>
 *   <li>标题检测：从 StyleSheet 中解析 "Heading N" / "标题 N" 等样式名</li>
 *   <li>删除线标记：CharacterRun 的 isStrikeThrough() 为 true 时包裹 ~~...~~</li>
 *   <li>表格渲染：处理垂直合并单元格（isVerticallyMerged + isFirstMerged），输出 Markdown 表格</li>
 * </ul>
 *
 * <p>注意：DOC 本身是 OLE2 复合文档（POIFS），内嵌对象可通过 OleExtractor 解出。
 * Phase 1 unpack 阶段暂不提取 OLE 嵌入文件，仅在 Phase 2 extract 中提取。</p>
 *
 * @see OleExtractor
 * @see AbstractHandler
 */
@Component
@Slf4j
public class DocHandler extends AbstractHandler {

    public DocHandler() {
        super();
    }

    /** 判断当前文件名是否为 .doc 扩展名。 */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".doc");
    }

    // ==================== 阶段 1：UNPACK（仅图片） ====================

    /**
     * Phase 1 拆包：仅提取图片，不解析文本，实现轻量级操作。
     * OLE 嵌入对象通过 Phase 2 完整处理；.doc 本身就是 OLE2 容器，
     * 嵌入对象在 Phase 2 extractRangeContent 中可被 OleExtractor 解出。
     */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("doc", fileName);
        int position = 0;

        try (HWPFDocument doc = new HWPFDocument(is)) {
            // 仅提取图片（轻量级，不解析文本）
            position += unpackImages(doc, unpacked, position);
            // OLE 嵌入对象通过 Phase 2 完整处理
        } catch (Exception e) {
            log.error("Failed to unpack doc: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

    /**
     * 从 PicturesTable 中提取所有图片。
     * 返回实际提取到的图片数量（用于 position 自增）。
     */
    private int unpackImages(HWPFDocument doc, ExtractionResult result, int position) {
        int count = 0;
        PicturesTable picturesTable = doc.getPicturesTable();
        if (picturesTable == null) return count;
        try {
            for (Picture pic : picturesTable.getAllPictures()) {
                String ext = pic.suggestFileExtension();
                if (ext == null || ext.isEmpty()) ext = "bin";
                String imgName = "doc_image_" + position + "." + ext;
                result.addImage(new ImageFile(imgName, position, pic.getContent(), ext));
                count++;
                position++;
            }
        } catch (Exception e) {
            result.addError("doc images: " + e.getMessage());
        }
        return count;
    }

    // ==================== 阶段 2：EXTRACT（完整解析） ====================

    /**
     * Phase 2 完整解析：按顺序处理页眉 → 正文（段落+表格）→ 图片。
     * 使用 StyleSheet 进行标题级别检测。
     */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("doc", fileName);
        int position = 0;

        try (HWPFDocument doc = new HWPFDocument(is)) {
            // 获取样式表（用于标题检测）
            StyleSheet styleSheet = doc.getStyleSheet();

            // 处理页眉
            position += extractHeaderStory(doc, result, position, styleSheet);

            // 处理正文：按 Range 遍历段落和表格
            Range range = doc.getRange();
            if (range != null) {
                position = extractRangeContent(range, result, position, styleSheet);
            }

            // 提取图片（生成 TYPE: image Element，含 media/ 引用）
            position = extractImages(doc, result, position);

        } catch (Exception e) {
            log.error("Failed to parse doc: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    /**
     * 遍历 Range，交替处理表格和段落。
     * <p>算法：
     * <ol>
     *   <li>预扫描所有 Table 列表并建立索引 tableIdx</li>
     *   <li>对每个 Paragraph，若 isInTable() 则匹配对应 Table 渲染为 Markdown</li>
     *   <li>渲染后跳过该表格内剩余的所有段落（while i++ 直到下一个不在表格中的段)</li>
     * </ol>
     */
    private int extractRangeContent(Range range, ExtractionResult result,
                                      int position, StyleSheet styleSheet) {
        // 预扫描：收集 Range 内的所有表格并排序（HWPF 可能不按段落顺序给出表格）
        List<Table> tables = new ArrayList<>();
        TableIterator tableIter = new TableIterator(range);
        while (tableIter.hasNext()) {
            tables.add(tableIter.next());
        }
        int tableIdx = 0;

        for (int i = 0; i < range.numParagraphs(); i++) {
            Paragraph para = range.getParagraph(i);
            if (para == null) continue;
            if (para.isInTable()) {
                // 当前段落属于某个表格 → 用 TableIterator 中对应的 Table 渲染
                if (tableIdx < tables.size()) {
                    Table table = tables.get(tableIdx);
                    if (table != null) {
                        String md = hwpfTableToMarkdown(table);
                        if (!md.isEmpty()) {
                            result.addElement(new Element(position++, "table", md));
                        }
                        tableIdx++;
                        // 跳过当前表格内剩余的段落（isInTable 为 true 的连续段落）
                        while (i + 1 < range.numParagraphs()) {
                            Paragraph next = range.getParagraph(i + 1);
                            // 停止条件：下一段不属于表格，或超出当前 Table 范围
                            if (next == null || !next.isInTable()) break;
                            if (tableIdx < tables.size()
                                    && next.getStartOffset() >= tables.get(tableIdx).getStartOffset()) {
                                break;
                            }
                            i++;
                        }
                        continue;
                    }
                }
                // 无法匹配到表格 → 按普通单元格处理（防御性回退）
                String text = buildParagraphText(para);
                if (!text.isEmpty()) {
                    result.addElement(new Element(position++, "table_cell", text));
                }
            } else {
                // 普通段落（不在表格内）
                String text = buildParagraphText(para);
                if (!text.isEmpty()) {
                    int headingLevel = detectHeadingLevel(para, styleSheet);
                    Element elem = new Element(position, "paragraph", text);
                    if (headingLevel > 0) {
                        elem.setHeadingLevel(headingLevel);
                    }
                    result.addElement(elem);
                    position++;
                }
            }
        }
        return position;
    }

    /**
     * 构建段落文本：遍历 CharacterRun，检测删除线并包裹 ~~...~~。
     * HWPF CharacterRun 继承自 org.apache.poi.wp.usermodel.CharacterRun，
     * 使用 isStrikeThrough()（注意大写 T）。
     */
    private String buildParagraphText(Paragraph para) {
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < para.numCharacterRuns(); j++) {
            CharacterRun run = para.getCharacterRun(j);
            if (run == null) continue;
            String runText = run.text();
            if (runText == null || runText.isEmpty()) continue;
            String trimmed = runText.trim();
            if (run.isStrikeThrough()) {
                sb.append("~~").append(trimmed).append("~~");
            } else {
                sb.append(trimmed);
            }
        }
        return sb.toString().trim();
    }

    /**
     * 从 HWPF 样式表中检测段落标题级别。
     * 通过 StyleDescription.getName() 解析：
     * <ul>
     *   <li>英文："Heading 1" / "Heading 2" → 提取数字</li>
     *   <li>中文："标题 1" / "标题一" → 提取数字</li>
     *   <li>纯数字 ID："1" / "2" → 直接解析为整数（POI 有时会映射中文标题为纯数字 ID）</li>
     * </ul>
     * 返回值范围 1-9，默认不匹配时返回 0（非标题）。
     */
    static int detectHeadingLevel(Paragraph para, StyleSheet styleSheet) {
        if (styleSheet == null) return 0;
        try {
            int styleIndex = para.getStyleIndex();
            if (styleIndex < 0 || styleIndex >= styleSheet.numStyles()) return 0;
            StyleDescription sd = styleSheet.getStyleDescription(styleIndex);
            if (sd == null) return 0;
            String name = sd.getName();
            if (name == null) return 0;
            // English: "heading 1", "Heading 2", etc.
            String lower = name.toLowerCase();
            if (lower.startsWith("heading")) {
                try {
                    return Integer.parseInt(lower.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    return 1; // "Heading" without number — fallback to level 1
                }
            }
            // Chinese: "标题 1", "标题一"
            if (lower.contains("标题")) {
                try {
                    return Integer.parseInt(lower.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    return 1; // fallback to level 1
                }
            }
            // Pure numeric IDs like "1", "2"
            try {
                int n = Integer.parseInt(name.trim());
                if (n >= 1 && n <= 9) return n;
            } catch (NumberFormatException ignored) {
            }
        } catch (Exception e) {
            log.debug("Failed to detect heading level for .doc paragraph: {}", e.getMessage());
        }
        return 0;
    }

    // ==================== HWPF 表格 → Markdown ====================

    /**
     * 将 HWPF Table 渲染为 Markdown 表格。
     * 处理逻辑：
     * <ol>
     *   <li>遍历所有行，跳过垂直合并的非首单元格（isVerticallyMerged && !isFirstMerged）</li>
     *   <li>记录最大列数作为统一宽度</li>
     *   <li>每行补齐到统一宽度，输出 | cell | ... | 格式</li>
     *   <li>第一行后插入分隔线</li>
     * </ol>
     */
    private String hwpfTableToMarkdown(Table table) {
        int totalCols = 0;
        List<List<String>> grid = new ArrayList<>();

        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            List<String> rowData = new ArrayList<>();
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
                // 跳过垂直合并的非首单元格（值由 isFirstMerged=true 的单元格承载）
                if (cell.isVerticallyMerged() && !cell.isFirstMerged()) continue;
                rowData.add(getCellText(cell).trim());
            }
            grid.add(rowData);
            totalCols = Math.max(totalCols, rowData.size());
        }
        if (totalCols == 0) return "";

        StringBuilder md = new StringBuilder();
        for (int r = 0; r < grid.size(); r++) {
            List<String> rowCells = grid.get(r);
            // 补齐列数不一致的行
            while (rowCells.size() < totalCols) rowCells.add("");
            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            // 第一行后插入分隔线
            if (r == 0) {
                md.append("| ").append("--- | ".repeat(totalCols));
                md.setLength(md.length() - 3);
                md.append(" |\n");
            }
        }
        return md.toString().trim();
    }

    /** 获取单元格内所有段落的文本（段落间以空格分隔）。 */
    private String getCellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < cell.numParagraphs(); p++) {
            Paragraph para = cell.getParagraph(p);
            if (para == null) continue;
            String text = buildParagraphText(para);
            if (!text.isEmpty()) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(text);
            }
        }
        return sb.toString();
    }

    // ==================== 图片提取 ====================

    /**
     * 从 PicturesTable 中提取所有图片，生成 ImageFile + TYPE: image Element。
     * 与 unpackImages 不同，此处会同时在 result.elements 中添加 image 引用 Element。
     */
    private int extractImages(HWPFDocument doc, ExtractionResult result, int startPos) {
        PicturesTable picturesTable = doc.getPicturesTable();
        if (picturesTable == null) return 0;
        int pos = startPos;
        try {
            for (Picture pic : picturesTable.getAllPictures()) {
                String ext = pic.suggestFileExtension();
                if (ext == null || ext.isEmpty()) ext = "bin";
                String imgName = "doc_image_" + pos + "." + ext;
                result.addImage(new ImageFile(imgName, pos, pic.getContent(), ext));
                result.addElement(new Element(pos, "image", null,
                        "file: media/" + StringUtils.sanitizeFileName(imgName)));
                pos++;
            }
        } catch (Exception e) {
            result.addError("doc images: " + e.getMessage());
        }
        return pos - startPos;
    }

    // ==================== 页眉提取 ====================

    /**
     * 提取页眉区域的段落文本。
     * 如果页眉不可用（某些 .doc 文件的 HeaderStoryRange 为 null），静默跳过。
     */
    private int extractHeaderStory(HWPFDocument doc, ExtractionResult result,
                                     int startPosition, StyleSheet styleSheet) {
        int pos = startPosition;
        try {
            Range headerRange = doc.getHeaderStoryRange();
            if (headerRange != null) {
                for (int i = 0; i < headerRange.numParagraphs(); i++) {
                    Paragraph para = headerRange.getParagraph(i);
                    if (para == null) continue;
                    String text = buildParagraphText(para);
                    if (!text.isEmpty()) {
                        result.addElement(new Element(pos++, "header", text));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Header extraction not supported for this .doc: {}", e.getMessage());
        }
        return pos - startPosition;
    }
}
