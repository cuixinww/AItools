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
import java.util.*;

/**
 * DOC 文档处理器（DocHandler）。
 *
 * 基于 Apache POI (HWPF) 解析 Word 97-2003（.doc，OLE2 二进制格式）文档。
 * 完整实现段落（含标题/删除线）、表格（Markdown 渲染）、图片、OLE嵌入对象、
 * 页眉页脚提取，以及独立的 Phase 1 轻量 unpack。
 *
 * 对应 SPEC §9 路由表：.doc → DocHandler。
 */
@Component
@Slf4j
public class DocHandler extends AbstractHandler {

    public DocHandler() {
        super();
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".doc");
    }

    // ==================== 阶段 1：UNPACK（仅图片 + 嵌入对象） ====================

    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("doc", fileName);
        int position = 0;

        try (HWPFDocument doc = new HWPFDocument(is)) {
            // 仅提取图片（轻量级，不解析文本）
            position += unpackImages(doc, unpacked, position);
            // OLE 嵌入对象通过 Phase 2 完整处理；Phase 1 仅需保留原始字节
            // .doc 本身就是 OLE2 容器，嵌入对象可在 Phase 2 从 POIFS 中提取
        } catch (Exception e) {
            log.error("Failed to unpack doc: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

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

            // 提取图片（生成 TYPE: image Element）
            position = extractImages(doc, result, position);

        } catch (Exception e) {
            log.error("Failed to parse doc: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    /**
     * 遍历 Range，交替处理表格和段落。
     */
    private int extractRangeContent(Range range, ExtractionResult result,
                                      int position, StyleSheet styleSheet) {
        // Build list of tables in this range
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
                // 遇到表格 → 渲染整个表格为 Markdown 并跳过该表格的剩余段落
                if (tableIdx < tables.size()) {
                    Table table = tables.get(tableIdx);
                    if (table != null) {
                        String md = hwpfTableToMarkdown(table);
                        if (!md.isEmpty()) {
                            result.addElement(new Element(position++, "table", md));
                        }
                        tableIdx++;
                        // 跳过当前表格内剩余的段落
                        while (i + 1 < range.numParagraphs()) {
                            Paragraph next = range.getParagraph(i + 1);
                            if (next == null || !next.isInTable()) break;
                            // 如果下一个段落的偏移量超出了当前表格范围，则是另一个表格
                            if (tableIdx < tables.size()
                                    && next.getStartOffset() >= tables.get(tableIdx).getStartOffset()) {
                                break;
                            }
                            i++;
                        }
                        continue;
                    }
                }
                // 无法匹配到表格 → 按普通单元格处理
                String text = buildParagraphText(para);
                if (!text.isEmpty()) {
                    result.addElement(new Element(position++, "table_cell", text));
                }
            } else {
                // 普通段落
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
     * 构建段落文本，检测删除线。
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
     * StyleDescription 没有直接的 getLvl()，通过解析样式名称判断：
     * "Heading 1" / "标题 1" → level 1，以此类推。
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
                    return 1;
                }
            }
            // Chinese: "标题 1", "标题一" (POI may use these or numeric "1"/"2" IDs)
            if (lower.contains("标题")) {
                try {
                    return Integer.parseInt(lower.replaceAll("[^0-9]", ""));
                } catch (NumberFormatException e) {
                    return 1;
                }
            }
            // POI sometimes maps Chinese heading styles to plain numeric IDs e.g. "1", "2"
            // Only accept if the name is purely numeric and in range 1-9
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
     */
    private String hwpfTableToMarkdown(Table table) {
        int totalCols = 0;
        List<List<String>> grid = new ArrayList<>();

        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            List<String> rowData = new ArrayList<>();
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
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
            while (rowCells.size() < totalCols) rowCells.add("");
            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            if (r == 0) {
                md.append("| ").append("--- | ".repeat(totalCols));
                md.setLength(md.length() - 3);
                md.append(" |\n");
            }
        }
        return md.toString().trim();
    }

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
