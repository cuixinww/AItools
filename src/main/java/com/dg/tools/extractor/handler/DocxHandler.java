package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.OleExtractor;
import com.dg.tools.extractor.model.*;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.xwpf.usermodel.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 *
 * OLE 对象通过 {@link OleExtractor} 进一步解出内部真实文件。
 */
public class DocxHandler implements DocumentHandler {

    /**
     * 是否支持该文件。匹配 .docx 与 .docm（启用宏的 Word 文档）。
     */
    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".docm");
    }

    // ==================== 阶段 1：UNPACK（拆包） ====================

    /**
     * 阶段 1 拆包：抽取图片与内嵌文件（不含文本解析）。
     */
    @Override
    public UnpackResult unpack(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        UnpackResult unpacked = new UnpackResult("docx", fileName);
        int position = 0;

        try (XWPFDocument doc = new XWPFDocument(is)) {
            // 正文段落（含表格内的）中的图片
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
            // 页眉 / 页脚中的图片
            unpackHeaderFooterImages(doc, unpacked, position);

            // 内嵌文件（普通附件 + OLE 对象）
            unpackEmbeddedFiles(doc, unpacked);
            unpackOleEmbeddings(doc, unpacked);

        } catch (Exception e) {
            throw new RuntimeException("Failed to unpack docx: " + fileName, e);
        }

        return unpacked;
    }

    /** 从单个段落的 run 中抽取内嵌图片（Excel/Word 图片以 XWPFPicture 形式挂在 run 上）。 */
    private int unpackImagesFromParagraph(XWPFParagraph para, UnpackResult result, int position) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                XWPFPictureData picData = pic.getPictureData();
                String picFileName = picData.getFileName();
                if (picFileName == null || picFileName.isEmpty()) {
                    // 缺少文件名时按位置自动命名，保证唯一
                    picFileName = "image_" + (position + count) + "." + picData.suggestFileExtension();
                }
                result.addImage(new ImageFile(picFileName, position + count,
                        picData.getData(), picData.suggestFileExtension()));
                count++;
            }
        }
        return count;
    }

    /** 抽取页眉与页脚中的图片，位置序号在正文图片之后继续累计。 */
    private void unpackHeaderFooterImages(XWPFDocument doc, UnpackResult result, int position) {
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

    /** 抽取 /word/embeddings/ 下的普通内嵌文件（排除 OLE 类型）。 */
    private void unpackEmbeddedFiles(XWPFDocument doc, UnpackResult result) {
        int pos = 0;
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String contentType = part.getContentType();
                // OLE 对象交给专门的 unpackOleEmbeddings 处理，这里跳过
                if (contentType != null && (contentType.contains("oleObject") || contentType.contains("ole"))) continue;

                String embeddedFileName = partName.substring(partName.lastIndexOf('/') + 1);
                if (embeddedFileName.isEmpty()) continue;

                byte[] fileBytes = readPartBytes(part);
                result.addEmbedded(new EmbeddedFile(embeddedFileName, pos++, fileBytes));
            }
        } catch (Exception e) {
            // 单个内嵌文件抽取失败不影响整体，仅记录错误
            result.addError("unpack embedded files: " + e.getMessage());
        }
    }

    /** 抽取 /word/embeddings/ 下的 OLE 对象，并用 OleExtractor 解出内部真实文件。 */
    private void unpackOleEmbeddings(XWPFDocument doc, UnpackResult result) {
        int pos = result.getEmbeddedFiles().size();
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String contentType = part.getContentType();
                if (contentType == null) continue;
                if (!contentType.contains("oleObject") && !contentType.contains("ole")) continue;

                byte[] oleBytes = readPartBytes(part);
                Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                for (Map.Entry<String, byte[]> entry : extracted.entrySet()) {
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), pos++, entry.getValue()));
                }
                if (extracted.isEmpty() && oleBytes.length > 0) {
                    // 无法解出的 OLE 也保留其原始字节，便于事后排查
                    String innerFileName = partName.substring(partName.lastIndexOf('/') + 1);
                    result.addEmbedded(new EmbeddedFile(innerFileName, pos++, oleBytes));
                }
            }
        } catch (Exception e) {
            result.addError("unpack OLE embeddings: " + e.getMessage());
        }
    }

    // ==================== 阶段 2：EXTRACT（解析） ====================

    /**
     * 阶段 2 解析：按文档原始元素顺序遍历，生成结构化 Element 列表。
     */
    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("docx", fileName);

        try (XWPFDocument doc = new XWPFDocument(is)) {
            int position = 0;

            // 1. 页眉 / 页脚 —— 先输出（返回新增元素数）
            position += extractHeadersFooters(doc, result, position);

            // 2. 正文段落、表格、图片
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    position += extractParagraph(para, result, position);
                } else if (element instanceof XWPFTable table) {
                    // 表格整体渲染为 Markdown 作为一个元素
                    String md = tableToMarkdown(table);
                    result.addElement(new Element(position++, "table", md));
                    // 同时抽取表格单元格内的图片
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph para : cell.getParagraphs()) {
                                extractParagraph(para, result, position);
                            }
                        }
                    }
                }
            }

            // 3. 内嵌文件（非 OLE）
            extractEmbedded(doc, result);

            // 4. OLE 内嵌文件
            extractOleEmbeddings(doc, result);

        } catch (IOException e) {
            throw new RuntimeException("Failed to parse docx: " + fileName, e);
        }

        return result;
    }

    /**
     * 解析单个段落：抽取其中的图片（作为 image 元素）与文字（作为 paragraph 元素）。
     * 返回本次新增的元素数量（图片 + 文字），供调用方推进 position。
     */
    private int extractParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            List<XWPFPicture> pictures = run.getEmbeddedPictures();
            for (XWPFPicture pic : pictures) {
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
        String text = para.getText();
        if (text != null && !text.isBlank()) {
            result.addElement(new Element(position + count, "paragraph", text.trim()));
            count++;
        }
        return count;
    }

    /** 抽取页眉 / 页脚中的段落与表格，返回新增元素数量。 */
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

    // ==================== 表格渲染 ====================

    /**
     * 将 XWPFTable 渲染为 Markdown 表格，并处理合并单元格（gridSpan 横向合并）：
     *   - 先计算表格最大列数（考虑 gridSpan）；
     *   - 对每个单元格，按 gridSpan 宽度填充多列（合并列后续补空）；
     *   - 首行之后补一行分隔线（---）。
     */
    private String tableToMarkdown(XWPFTable table) {
        // 构建归一化网格：把含有 gridSpan 的单元格按跨度展开
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
            List<String> rowCells = new ArrayList<>();

            for (XWPFTableCell cell : row.getTableCells()) {
                int span = 1;
                var tcPr = cell.getCTTc().getTcPr();
                if (tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                    span = tcPr.getGridSpan().getVal().intValue();
                }
                String cellText = cell.getText().trim();
                rowCells.add(cellText);
                // 合并列：后续列补空，保持列对齐
                for (int s = 1; s < span; s++) {
                    rowCells.add("");
                }
            }
            // 末尾若列数不足则补空，避免 Markdown 表格列数不一致
            while (rowCells.size() < totalCols) {
                rowCells.add("");
            }

            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            if (r == 0) {
                // 首行下方补分隔行
                md.append("| ").append(rowCells.stream().map(c -> "---")
                        .collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
            }
        }
        return md.toString().trim();
    }

    // ==================== 内嵌 / OLE 抽取 ====================

    /** 阶段 2：抽取普通内嵌文件（非 OLE），并记录一个 embed 元素指向它。 */
    private void extractEmbedded(XWPFDocument doc, ExtractionResult result) {
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String contentType = part.getContentType();
                if (contentType != null && (contentType.contains("oleObject") || contentType.contains("ole"))) continue;

                String embeddedFileName = partName.substring(partName.lastIndexOf('/') + 1);
                if (embeddedFileName.isEmpty()) continue;

                byte[] fileBytes = readPartBytes(part);
                int embedPosition = result.getElements().size();
                result.addEmbedded(new EmbeddedFile(embeddedFileName, embedPosition, fileBytes));
                result.addElement(new Element(embedPosition, "embed",
                        null, "file: " + embeddedFileName));
            }
        } catch (Exception e) {
            result.addError("embedded files: " + e.getMessage());
        }
    }

    /** 阶段 2：抽取 OLE 内嵌对象，用 OleExtractor 解出内部真实文件并记录 embed 元素。 */
    private void extractOleEmbeddings(XWPFDocument doc, ExtractionResult result) {
        try {
            OPCPackage pkg = doc.getPackage();
            for (PackagePart part : pkg.getParts()) {
                String partName = part.getPartName().getName();
                if (partName == null || !partName.contains("embeddings")) continue;

                String contentType = part.getContentType();
                if (contentType == null) continue;
                if (!contentType.contains("oleObject") && !contentType.contains("ole")) continue;

                byte[] oleBytes = readPartBytes(part);
                Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                for (var entry : extracted.entrySet()) {
                    int embedPosition = result.getElements().size();
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), embedPosition, entry.getValue()));
                    result.addElement(new Element(embedPosition, "embed",
                            null, "file: " + entry.getKey()));
                }
                if (extracted.isEmpty() && oleBytes.length > 0) {
                    // 无法解出的 OLE 保留原始字节
                    String innerFileName = partName.substring(partName.lastIndexOf('/') + 1);
                    int embedPosition = result.getElements().size();
                    result.addEmbedded(new EmbeddedFile(innerFileName, embedPosition, oleBytes));
                    result.addElement(new Element(embedPosition, "embed",
                            null, "file: " + innerFileName));
                }
            }
        } catch (Exception e) {
            result.addError("OLE embeddings: " + e.getMessage());
        }
    }

    // ==================== 工具方法 ====================

    /** 读取 OPC 包中某个 Part 的全部原始字节。 */
    private static byte[] readPartBytes(PackagePart part) throws IOException {
        try (InputStream partIs = part.getInputStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = partIs.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        }
    }
}
