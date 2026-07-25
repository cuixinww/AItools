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
                    String md = tableToMarkdown(table);
                    result.addElement(new Element(position++, "table", md));
                    for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) {
                            for (XWPFParagraph para : cell.getParagraphs()) {
                                position += unpackImagesFromParagraph(para, result, position);
                            }
                        }
                    }
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

    private int extractParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        int count = unpackImagesFromParagraph(para, result, position);
        String text = para.getText();
        if (text != null && !text.isBlank()) {
            result.addElement(new Element(position + count, "paragraph", text.trim()));
            count++;
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

    // ==================== 表格渲染 ====================

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
            List<String> rowCells = new ArrayList<>();

            for (XWPFTableCell cell : row.getTableCells()) {
                int span = 1;
                var tcPr = cell.getCTTc().getTcPr();
                if (tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                    span = tcPr.getGridSpan().getVal().intValue();
                }
                String cellText = cell.getText().trim();
                rowCells.add(cellText);
                for (int s = 1; s < span; s++) {
                    rowCells.add("");
                }
            }
            while (rowCells.size() < totalCols) {
                rowCells.add("");
            }

            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            if (r == 0) {
                md.append("| ").append(rowCells.stream().map(c -> "---")
                        .collect(java.util.stream.Collectors.joining(" | "))).append(" |\n");
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
