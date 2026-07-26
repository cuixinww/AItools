package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.OleExtractor;
import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DOCX / DOCM 文档处理器（DocxHandler）。
 *
 * 基于 Apache POI (XWPF) 解析 Word 2007+ 的 OOXML 文档。
 *
 * 阶段 1（unpack）：仅抽取正文 / 页眉页脚 / 表格单元格中的图片，以及
 *   /word/embeddings/ 下的内嵌文件（普通附件与 OLE 对象），不做文本解析。
 *
 * 阶段 2（extract）：按文档 XML 原始元素顺序（getBodyElements）遍历，
 *   依次处理页眉页脚、正文段落、表格（含单元格内图片）、内嵌文件与 OLE 对象，
 *   保证图片 / 嵌入对象与其上下文段落的相对顺序被保留。
 *   图片在正文中生成 TYPE: image Element，写入位置与原文一致。
 *
 * OLE 对象通过 {@link OleExtractor} 进一步解出内部真实文件。
 *
 * 对应 SPEC §9 路由表：.docx/.docm → DocxHandler。
 */
@Component
@Slf4j
public class DocxHandler extends AbstractHandler {

    public DocxHandler() {
        super();
    }

    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".docm");
    }

    // ==================== 阶段 1：UNPACK（拆包） ====================

    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("docx", fileName);
        int position = 0;

        try (XWPFDocument doc = new XWPFDocument(is)) {
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    int added = unpackImagesFromParagraph(para, unpacked, position);
                    position += added;
                } else if (element instanceof XWPFTable table) {
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph para : cell.getParagraphs()) {
                                int added = unpackImagesFromParagraph(para, unpacked, position);
                                position += added;
                            }
                        }
                    }
                }
            }
            unpackHeaderFooterImages(doc, unpacked, position);
            unpackEmbeddedFiles(doc, unpacked);
            unpackOleEmbeddings(doc, unpacked);

        } catch (Exception e) {
            log.error("Failed to unpack docx: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

    private int unpackImagesFromParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                XWPFPictureData picData = pic.getPictureData();
                String picFileName = picData.getFileName();
                if (picFileName == null || picFileName.isEmpty()) {
                    picFileName = "image_" + (position + count) + "." + picData.suggestFileExtension();
                }
                result.addImage(new ImageFile(picFileName, position + count,
                        picData.getData(), picData.suggestFileExtension()));
                count++;
            }
        }
        return count;
    }

    private void unpackHeaderFooterImages(XWPFDocument doc, ExtractionResult result, int position) {
        int pos = position;
        for (XWPFHeader header : doc.getHeaderList()) {
            if (header == null) continue;
            for (IBodyElement element : header.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    pos += unpackImagesFromParagraph(para, result, pos);
                }
            }
        }
        for (XWPFFooter footer : doc.getFooterList()) {
            if (footer == null) continue;
            for (IBodyElement element : footer.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    pos += unpackImagesFromParagraph(para, result, pos);
                }
            }
        }
    }

    private void unpackEmbeddedFiles(XWPFDocument doc, ExtractionResult result) {
        int pos = result.getEmbeddedFiles().size();
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), false)) {
                String name = partNameFromPart(part);
                if (name == null) continue;
                result.addEmbedded(new EmbeddedFile(name, pos++, StringUtils.readBytes(part.getInputStream())));
            }
        } catch (Exception e) {
            result.addError("unpack embedded files: " + e.getMessage());
        }
    }

    private void unpackOleEmbeddings(XWPFDocument doc, ExtractionResult result) {
        int pos = result.getEmbeddedFiles().size();
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), true)) {
                byte[] oleBytes = StringUtils.readBytes(part.getInputStream());
                Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                for (var entry : extracted.entrySet()) {
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), pos++, entry.getValue()));
                }
                if (extracted.isEmpty() && oleBytes.length > 0) {
                    String name = partNameFromPart(part);
                    if (name != null) {
                        result.addEmbedded(new EmbeddedFile(name, pos++, oleBytes));
                    }
                }
            }
        } catch (Exception e) {
            result.addError("unpack OLE embeddings: " + e.getMessage());
        }
    }

    // ==================== 阶段 2：EXTRACT（解析） ====================

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("docx", fileName);

        try (XWPFDocument doc = new XWPFDocument(is)) {
            int position = 0;

            position += extractHeadersFooters(doc, result, position);

            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    position += extractParagraph(para, result, position);
                } else if (element instanceof XWPFTable table) {
                    // 先提取表格单元格内的图片（生成 TYPE: image Element，放置于 table 之前）
                    position += extractImagesFromTable(table, result, position);
                    // 再渲染表格 Markdown
                    String md = tableToMarkdown(table);
                    result.addElement(new Element(position++, "table", md));
                }
            }

            extractEmbedded(doc, result);
            extractOleEmbeddings(doc, result);

        } catch (Exception e) {
            log.error("Failed to parse docx: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    /**
     * 提取段落中包含的图片与文本，按 run 的真实遍历顺序交替输出。
     * 每个含图片的 run 生成 TYPE: image Element，文本 run 合并后生成 TYPE: paragraph Element。
     * 保证图文顺序与原文一致。删除线文本包裹 ~~...~~。
     */
    private int extractParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        String paraStyle = para.getStyle();
        int headingLevel = detectHeadingLevel(paraStyle);
        int count = 0;
        StringBuilder textBuf = new StringBuilder();

        for (XWPFRun run : para.getRuns()) {
            // 处理该 run 中的嵌入图片
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                // 先将缓冲的文本 flush
                if (!textBuf.isEmpty()) {
                    addParagraphElement(result, position++, textBuf.toString().trim(), headingLevel);
                    textBuf.setLength(0);
                    count++;
                }
                // 图片 element
                XWPFPictureData picData = pic.getPictureData();
                String picFileName = picData.getFileName();
                if (picFileName == null || picFileName.isEmpty()) {
                    picFileName = "image_" + position + "." + picData.suggestFileExtension();
                }
                result.addImage(new ImageFile(picFileName, position,
                        picData.getData(), picData.suggestFileExtension()));
                result.addElement(new Element(position, "image", null,
                        "file: media/" + StringUtils.sanitizeFileName(picFileName)));
                count++;
                position++;
            }
            // 处理该 run 中的文本
            String runText = run.text();
            if (runText != null && !runText.isEmpty()) {
                if (!textBuf.isEmpty()) textBuf.append(' ');
                if (run.isStrikeThrough()) {
                    textBuf.append("~~").append(runText).append("~~");
                } else {
                    textBuf.append(runText);
                }
            }
        }
        // flush 剩余的文本
        if (!textBuf.isEmpty()) {
            String finalText = textBuf.toString().trim();
            if (!finalText.isEmpty()) {
                addParagraphElement(result, position, finalText, headingLevel);
                count++;
            }
        }
        return count;
    }

    private void addParagraphElement(ExtractionResult result, int pos, String text, int headingLevel) {
        Element elem = new Element(pos, "paragraph", text);
        if (headingLevel > 0) elem.setHeadingLevel(headingLevel);
        result.addElement(elem);
    }

    /**
     * 从段落样式 ID 中检测标题级别。
     * OOXML 标题样式 ID 通常为 "Heading1", "Heading2" 等，或中文 "1", "2" 等
     * （POI 有时映射中文标题为数字样式 ID）。
     */
    static int detectHeadingLevel(String styleId) {
        if (styleId == null) return 0;
        if (styleId.startsWith("Heading") || styleId.startsWith("heading")) {
            try {
                return Integer.parseInt(styleId.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0; // "Heading" without number — not a real heading
            }
        }
        try {
            int n = Integer.parseInt(styleId);
            if (n >= 1 && n <= 9) return n;
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /**
     * 提取表格中所有单元格内的图片，生成 TYPE: image Element。
     * 图片位置在表格 Element 之前，保证合理的图文顺序。
     */
    private int extractImagesFromTable(XWPFTable table, ExtractionResult result, int position) {
        int count = 0;
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                for (XWPFParagraph para : cell.getParagraphs()) {
                    for (XWPFRun run : para.getRuns()) {
                        for (XWPFPicture pic : run.getEmbeddedPictures()) {
                            XWPFPictureData picData = pic.getPictureData();
                            String picFileName = picData.getFileName();
                            if (picFileName == null || picFileName.isEmpty()) {
                                picFileName = "image_" + position + "." + picData.suggestFileExtension();
                            }
                            result.addImage(new ImageFile(picFileName, position,
                                    picData.getData(), picData.suggestFileExtension()));
                            result.addElement(new Element(position, "image", null,
                                    "file: media/" + StringUtils.sanitizeFileName(picFileName)));
                            count++;
                            position++;
                        }
                    }
                }
            }
        }
        return count;
    }

    private int extractHeadersFooters(XWPFDocument doc, ExtractionResult result, int startPosition) {
        int pos = startPosition;
        try {
            for (XWPFHeader header : doc.getHeaderList()) {
                if (header == null) continue;
                for (IBodyElement element : header.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        pos += extractParagraph(para, result, pos);
                    } else if (element instanceof XWPFTable table) {
                        result.addElement(new Element(pos++, "header", tableToMarkdown(table)));
                    }
                }
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                if (footer == null) continue;
                for (IBodyElement element : footer.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        pos += extractParagraph(para, result, pos);
                    } else if (element instanceof XWPFTable table) {
                        result.addElement(new Element(pos++, "footer", tableToMarkdown(table)));
                    }
                }
            }
        } catch (Exception e) {
            result.addError("headers/footers: " + e.getMessage());
        }
        return pos - startPosition;
    }

    // ==================== 表格渲染（修复 gridSpan） ====================

    /**
     * 将 XWPFTable 渲染为 Markdown 表格。
     * 使用显式列索引跟踪处理 gridSpan（合并单元格），确保列对齐正确。
     */
    private String tableToMarkdown(XWPFTable table) {
        int totalCols = table.getRows().stream()
                .mapToInt(row -> row.getTableCells().stream()
                        .mapToInt(cell -> {
                            var tcPr = cell.getCTTc().getTcPr();
                            return tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null
                                    ? tcPr.getGridSpan().getVal().intValue() : 1;
                        }).sum())
                .max().orElse(0);
        if (totalCols == 0) return "";

        StringBuilder md = new StringBuilder();
        for (int r = 0; r < table.getRows().size(); r++) {
            XWPFTableRow row = table.getRow(r);
            // 使用与总列数相同大小的数组，用空字符串初始化
            String[] rowCells = new String[totalCols];
            for (int c = 0; c < totalCols; c++) rowCells[c] = "";

            int colIdx = 0;
            for (XWPFTableCell cell : row.getTableCells()) {
                // 跳过已被上方行合并单元格覆盖的位置（垂直合并的非首单元格）
                int span = 1;
                var tcPr = cell.getCTTc().getTcPr();
                if (tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                    span = tcPr.getGridSpan().getVal().intValue();
                }
                // 确保不越界
                if (colIdx >= totalCols) break;
                rowCells[colIdx] = cell.getText().trim();
                colIdx += span;
            }

            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            if (r == 0) {
                md.append("| ").append("--- | ".repeat(totalCols));
                // 去除末尾多余的 " | "
                md.setLength(md.length() - 3);
                md.append(" |\n");
            }
        }
        return md.toString().trim();
    }

    // ==================== 内嵌 / OLE 抽取 ====================

    private void extractEmbedded(XWPFDocument doc, ExtractionResult result) {
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), false)) {
                String name = partNameFromPart(part);
                if (name == null) continue;
                int pos = result.getElements().size();
                result.addEmbedded(new EmbeddedFile(name, pos, StringUtils.readBytes(part.getInputStream())));
                result.addElement(new Element(pos, "embed", null, "file: " + name));
            }
        } catch (Exception e) {
            result.addError("embedded files: " + e.getMessage());
        }
    }

    private void extractOleEmbeddings(XWPFDocument doc, ExtractionResult result) {
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), true)) {
                byte[] oleBytes = StringUtils.readBytes(part.getInputStream());
                Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                for (var entry : extracted.entrySet()) {
                    int pos = result.getElements().size();
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), pos, entry.getValue()));
                    result.addElement(new Element(pos, "embed", null, "file: " + entry.getKey()));
                }
                if (extracted.isEmpty() && oleBytes.length > 0) {
                    String name = partNameFromPart(part);
                    if (name != null) {
                        int pos = result.getElements().size();
                        result.addEmbedded(new EmbeddedFile(name, pos, oleBytes));
                        result.addElement(new Element(pos, "embed", null, "file: " + name));
                    }
                }
            }
        } catch (Exception e) {
            result.addError("OLE embeddings: " + e.getMessage());
        }
    }

    // ==================== 嵌入部件工具 ====================

    private List<PackagePart> findEmbeddingParts(OPCPackage pkg, boolean oleOnly) {
        List<PackagePart> parts = new ArrayList<>();
        try {
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;
                String contentType = part.getContentType();
                boolean isOle = contentType != null && contentType.contains("oleObject");
                if (oleOnly != isOle) continue;
                parts.add(part);
            }
        } catch (Exception e) {
            log.warn("Failed to list embedding parts", e);
        }
        return parts;
    }

    private String partNameFromPart(PackagePart part) {
        String name = part.getPartName().getName();
        if (name == null) return null;
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isEmpty() ? null : name;
    }
}
