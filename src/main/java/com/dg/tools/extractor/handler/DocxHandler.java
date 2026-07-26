package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.OleExtractor;
import com.dg.tools.extractor.model.*;
import com.dg.tools.extractor.util.StringUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DOCX / DOCM 文档处理器。
 *
 * 基于 Apache POI (XWPF) 解析 Word 2007+ OOXML 文档，支持以下特性：
 * <ul>
 *   <li>Phase 1 UNPACK：仅抽取图片（正文/页眉页脚/表格内）和内嵌文件，不解析文本</li>
 *   <li>Phase 2 EXTRACT：按文档 XML 原始元素顺序遍历（getBodyElements），保留图文相对位置</li>
 *   <li>标题检测：从段落样式 ID 解析 Heading1~Heading9 级别</li>
 *   <li>删除线标记：run 中 isStrikeThrough() 为 true 的文本包裹 ~~...~~</li>
 *   <li>表格渲染：使用 gridSpan 处理合并单元格（水平+垂直合并），输出正确 Markdown 表格</li>
 *   <li>OLE 对象：通过 OleExtractor 解出内部真实文件并递归拆包</li>
 * </ul>
 *
 * <p>图片定位策略：段落中的图片与文本交替出现时，按 run 的真实遍历顺序生成 Element，
 * 确保 body.md 中的图片引用位置与原文中的视觉位置一致。</p>
 *
 * @see OleExtractor
 * @see AbstractHandler
 */
@Component
@Slf4j
public class DocxHandler extends AbstractHandler {

    public DocxHandler() {
        super();
    }

    /** 判断当前文件名是否为 .docx 或 .docm 扩展名。 */
    @Override
    public boolean supports(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".docx") || lower.endsWith(".docm");
    }

    // ==================== 阶段 1：UNPACK（拆包） ====================

    /**
     * Phase 1 拆包：仅提取图片（正文/页眉页脚/表格内）和内嵌文件，
     * 不做文本解析以实现轻量级操作。
     * 处理流程：正文图片 → 表内图片 → 页眉页脚图片 → 普通嵌入文件 → OLE 嵌入
     */
    @Override
    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult unpacked = ExtractionResult.of("docx", fileName);
        int position = 0;

        try (XWPFDocument doc = new XWPFDocument(is)) {
            // 遍历正文元素，提取段落和表格中的图片
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    int added = unpackImagesFromParagraph(para, unpacked, position);
                    position += added;
                } else if (element instanceof XWPFTable table) {
                    // 表格中嵌套的段落（如单元格内的插图）
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
            // 提取页眉页脚中的图片
            unpackHeaderFooterImages(doc, unpacked, position);
            // 提取普通嵌入文件（如附件、图表等）
            unpackEmbeddedFiles(doc, unpacked);
            // 提取 OLE 对象（可能包含嵌入的 Word/Excel 文档）
            unpackOleEmbeddings(doc, unpacked);

        } catch (Exception e) {
            log.error("Failed to unpack docx: {}", fileName, e);
            unpacked.addError("unpack error: " + e.getMessage());
        }

        return unpacked;
    }

    /**
     * 从段落的所有 run 中提取内嵌图片，记录到结果中。
     * 返回实际提取到的图片数量（用于 position 自增）。
     */
    private int unpackImagesFromParagraph(XWPFParagraph para, ExtractionResult result, int position) {
        int count = 0;
        for (XWPFRun run : para.getRuns()) {
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                XWPFPictureData picData = pic.getPictureData();
                // 优先使用 OOXML 中的原始文件名，无则自动生成
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

    /**
     * 遍历所有页眉和页脚，提取其中的图片。
     * 使用局部变量 pos 追踪位置序号，避免外部 position 受干扰。
     */
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

    /**
     * 从 OPCPackage 的 /word/embeddings/ 部分提取普通内嵌文件。
     * oleOnly=false 确保只获取非 OLE 类型的嵌入部件。
     */
    private void unpackEmbeddedFiles(XWPFDocument doc, ExtractionResult result) {
        // 从当前已有 embeddedFiles 数量开始编号，保证 pos 单调递增
        int pos = result.getEmbeddedFiles().size();
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), false)) {
                String name = partNameFromPart(part);
                if (name == null) continue;
                try (InputStream in = part.getInputStream()) {
                    result.addEmbedded(new EmbeddedFile(name, pos++, StringUtils.readBytes(in)));
                }
            }
        } catch (Exception e) {
            result.addError("unpack embedded files: " + e.getMessage());
        }
    }

    /**
     * 从 OPCPackage 的 /word/embeddings/ 部分提取 OLE 对象，
     * 通过 OleExtractor 进一步解出内部真实文件。
     * oleOnly=true 确保只获取 OLE 类型的嵌入部件。
     * 若 OleExtractor 未解出任何文件（非法 OLE），则保留原始字节作为 fallback。
     */
    private void unpackOleEmbeddings(XWPFDocument doc, ExtractionResult result) {
        int pos = result.getEmbeddedFiles().size();
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), true)) {
                byte[] oleBytes;
                try (InputStream in = part.getInputStream()) {
                    oleBytes = StringUtils.readBytes(in);
                }
                Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
                for (var entry : extracted.entrySet()) {
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), pos++, entry.getValue()));
                }
                // OLE 解析未成功 → 回退为原始字节内嵌文件
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

    /**
     * Phase 2 解析：完整提取文档内容。
     * 处理顺序：页眉页脚 → 正文段落/表格（含内联 OLE embed）→ 内嵌文件
     * 保证图文/OLE 相对顺序与原文一致。
     */
    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("docx", fileName);

        try (XWPFDocument doc = new XWPFDocument(is)) {
            int position = 0;

            // 单次遍历构建 OLE 关系映射和数据映射
            var oleMaps = buildOleMaps(doc);
            Map<String, List<String>> oleRelFiles = oleMaps.relFileMap;
            Map<String, byte[]> oleDataMap = oleMaps.dataMap;

            // 先处理页眉页脚（排在正文之前）
            position += extractHeadersFooters(doc, result, position, oleRelFiles, oleDataMap);

            // 按文档元素顺序遍历正文
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph para) {
                    position += extractParagraph(para, result, position, oleRelFiles, oleDataMap);
                } else if (element instanceof XWPFTable table) {
                    // 先提取表格单元格内的图片（放在 table Element 之前）
                    position += extractImagesFromTable(table, result, position);
                    // 再渲染表格为 Markdown
                    String md = tableToMarkdown(table);
                    result.addElement(new Element(position++, "table", md));
                }
            }

            // 非 OLE 类型的普通内嵌文件（如嵌入的 PDF、ZIP 等），跳过已由 OLE 处理的文件
            extractEmbedded(doc, result, oleDataMap.keySet());

            // 收集已通过 extractParagraph 内联定位的 OLE 文件名
            java.util.Set<String> placedOleFiles = new java.util.HashSet<>();
            for (var emb : result.getEmbeddedFiles()) {
                placedOleFiles.add(emb.getFileName());
            }
            // 剩余未定位的 OLE 文件追加到末尾（容器级 OLE，无段落引用）
            for (var entry : oleDataMap.entrySet()) {
                if (!placedOleFiles.contains(entry.getKey())) {
                    int pos = result.getElements().size();
                    result.addEmbedded(new EmbeddedFile(entry.getKey(), pos, entry.getValue()));
                    result.addElement(new Element(pos, "embed", null, "file: " + entry.getKey()));
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse docx: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }

    /**
     * 提取段落中包含的图片、OLE 嵌入与文本，按 run 的真实遍历顺序交替输出。
     * <p>处理逻辑：
     * <ol>
     *   <li>遇到图片：先将已积累的文本 flush 为一个 paragraph Element，然后生成 image Element</li>
     *   <li>遇到 OLE 对象：将累积文本 flush 后插入 embed Element（与图片同级处理）</li>
     *   <li>遇到文本：追加到 StringBuilder 缓冲区（不同 run 之间以空格分隔）</li>
     *   <li>删除线文本：用 ~~ 包裹</li>
     * </ol>
     */
    private int extractParagraph(XWPFParagraph para, ExtractionResult result, int position,
                                  Map<String, List<String>> oleRelFiles,
                                  Map<String, byte[]> oleDataMap) {
        String paraStyle = para.getStyle();
        int headingLevel = detectHeadingLevel(paraStyle);
        int count = 0;
        StringBuilder textBuf = new StringBuilder();

        for (XWPFRun run : para.getRuns()) {
            // 处理该 run 中的 OLE 嵌入对象（在图片和文本之前检查）
            int oleCount = extractOleFromRun(run, result, position, oleRelFiles, oleDataMap);
            if (oleCount > 0) {
                // flush 累积文本
                if (!textBuf.isEmpty()) {
                    addParagraphElement(result, position++, textBuf.toString().trim(), headingLevel);
                    textBuf.setLength(0);
                    count++;
                }
                position += oleCount;
                count += oleCount;
            }

            // 处理该 run 中的嵌入图片
            for (XWPFPicture pic : run.getEmbeddedPictures()) {
                // 先将缓冲的文本 flush 为段落 Element
                if (!textBuf.isEmpty()) {
                    addParagraphElement(result, position++, textBuf.toString().trim(), headingLevel);
                    textBuf.setLength(0);
                    count++;
                }
                // 生成图片 Element（addImage + image 引用）
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
                // 删除线标记
                if (run.isStrikeThrough()) {
                    textBuf.append("~~").append(runText).append("~~");
                } else {
                    textBuf.append(runText);
                }
            }
        }
        // flush 剩余的文本（段尾可能没有图片了）
        if (!textBuf.isEmpty()) {
            String finalText = textBuf.toString().trim();
            if (!finalText.isEmpty()) {
                addParagraphElement(result, position, finalText, headingLevel);
                count++;
            }
        }
        return count;
    }

    /**
     * 检查 XWPFRun 中是否包含 OLE 嵌入引用，是则添加 embed Element + EmbeddedFile。
     * 通过扫描 run 的 CTR XML 中是否包含 OLE 关系的 r:id 来判断。
     */
    private int extractOleFromRun(XWPFRun run, ExtractionResult result, int position,
                                   Map<String, List<String>> oleRelFiles,
                                   Map<String, byte[]> oleDataMap) {
        if (oleRelFiles.isEmpty()) return 0;
        String runXml = run.getCTR().xmlText();
        int count = 0;
        for (var entry : oleRelFiles.entrySet()) {
            String relId = entry.getKey();
            if (!runXml.contains("r:id=\"" + relId + "\"")) continue;
            for (String fileName : entry.getValue()) {
                byte[] data = oleDataMap.get(fileName);
                if (data != null) {
                    result.addEmbedded(new EmbeddedFile(fileName, position, data));
                    result.addElement(new Element(position, "embed", null, "file: " + fileName));
                    position++;
                    count++;
                }
            }
        }
        return count;
    }

    /** 创建一个 paragraph Element，如果是标题则设置 headingLevel。 */
    private void addParagraphElement(ExtractionResult result, int pos, String text, int headingLevel) {
        Element elem = new Element(pos, "paragraph", text);
        if (headingLevel > 0) elem.setHeadingLevel(headingLevel);
        result.addElement(elem);
    }

    /**
     * 从段落样式 ID 中检测标题级别。
     * OOXML 标题样式 ID 格式：
     *   - 英文："Heading1", "Heading2" ... → 提取末尾数字
     *   - 中文（POI 映射）："1", "2" ... → 直接解析为整数
     * 返回值范围 1-9，0 表示非标题。
     */
    static int detectHeadingLevel(String styleId) {
        if (styleId == null) return 0;
        // 匹配 "HeadingN" 或 "headingN"
        if (styleId.startsWith("Heading") || styleId.startsWith("heading")) {
            try {
                return Integer.parseInt(styleId.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                return 0; // "Heading" without number — 不是真正的标题
            }
        }
        // 纯数字样式 ID（如 POI 映射的中文标题 "1", "2"）
        try {
            int n = Integer.parseInt(styleId);
            if (n >= 1 && n <= 9) return n;
        } catch (NumberFormatException ignored) {
        }
        return 0;
    }

    /**
     * 提取表格中所有单元格内的图片，生成 TYPE: image Element。
     * 图片元素置于整个表格 Element 之前，保证合理的图文顺序。
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

    /**
     * 提取页眉页脚中的段落和表格。
     * 段落保持原有文本结构（含标题检测和删除线标记），
     * 表格整体渲染为 Markdown。
     */
    private int extractHeadersFooters(XWPFDocument doc, ExtractionResult result, int startPosition,
                                       Map<String, List<String>> oleRelFiles,
                                       Map<String, byte[]> oleDataMap) {
        int pos = startPosition;
        try {
            for (XWPFHeader header : doc.getHeaderList()) {
                if (header == null) continue;
                for (IBodyElement element : header.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        pos += extractParagraph(para, result, pos, oleRelFiles, oleDataMap);
                    } else if (element instanceof XWPFTable table) {
                        result.addElement(new Element(pos++, "header", tableToMarkdown(table)));
                    }
                }
            }
            for (XWPFFooter footer : doc.getFooterList()) {
                if (footer == null) continue;
                for (IBodyElement element : footer.getBodyElements()) {
                    if (element instanceof XWPFParagraph para) {
                        pos += extractParagraph(para, result, pos, oleRelFiles, oleDataMap);
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
     * <p>算法：
     * <ol>
     *   <li>扫描所有行，计算最大列数（考虑 gridSpan 合并单元格的跨度）</li>
     *   <li>每行用 String[] 数组填充，按实际 span 值跳过已合并位置</li>
     *   <li>垂直合并通过跳过非首单元格处理（POI 会在 getText() 中包含合并值，但我们显式跳过）</li>
     * </ol>
     */
    private String tableToMarkdown(XWPFTable table) {
        // 计算最大列数（考虑 gridSpan）
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
                // 读取 gridSpan（水平合并跨度）
                int span = 1;
                var tcPr = cell.getCTTc().getTcPr();
                if (tcPr != null && tcPr.getGridSpan() != null && tcPr.getGridSpan().getVal() != null) {
                    span = tcPr.getGridSpan().getVal().intValue();
                }
                // 越界保护：跳过已被上方行合并覆盖的位置
                if (colIdx >= totalCols) break;
                rowCells[colIdx] = cell.getText().trim();
                colIdx += span;
            }

            md.append("| ").append(String.join(" | ", rowCells)).append(" |\n");
            if (r == 0) {
                // 第一行后插入分隔线 | --- | --- | ... |
                md.append("| ").append("--- | ".repeat(totalCols));
                // 去除末尾多余的 " | "
                md.setLength(md.length() - 3);
                md.append(" |\n");
            }
        }
        return md.toString().trim();
    }

    // ==================== 内嵌 / OLE 抽取 ====================

    /**
     * 从 OPCPackage 提取普通嵌入文件（附件），在 body.md 中生成 embed Element。
     */
    private void extractEmbedded(XWPFDocument doc, ExtractionResult result,
                                  java.util.Set<String> alreadyProcessed) {
        try {
            for (PackagePart part : findEmbeddingParts(doc.getPackage(), false)) {
                String name = partNameFromPart(part);
                if (name == null || alreadyProcessed.contains(name)) continue;
                int pos = result.getElements().size();
                try (InputStream in = part.getInputStream()) {
                    result.addEmbedded(new EmbeddedFile(name, pos, StringUtils.readBytes(in)));
                }
                result.addElement(new Element(pos, "embed", null, "file: " + name));
            }
        } catch (Exception e) {
            result.addError("embedded files: " + e.getMessage());
        }
    }

    /**
     * 单次遍历构建 OLE 关系映射和数据映射。
     * 从 document.xml.rels 中收集指向 embeddings/ 的关系，
     * 关系类型为 oleObject 或 package 的视为 OLE 对象。
     * 遍历 embedding parts 时以关系条目为主体，兼容手动构造的 OPC 包。
     */
    private OleMaps buildOleMaps(XWPFDocument doc) {
        Map<String, byte[]> dataMap = new java.util.LinkedHashMap<>();
        Map<String, List<String>> relFileMap = new java.util.LinkedHashMap<>();

        // 1. 扫描 document.xml.rels，收集 OLE 关系
        java.util.Map<String, String> relTargets = new java.util.LinkedHashMap<>();
        try {
            PackagePart docPart = doc.getPackage().getPart(
                    PackagingURIHelper.createPartName("/word/document.xml"));
            for (PackageRelationship rel : docPart.getRelationships()) {
                String target = rel.getTargetURI().toString();
                String relType = rel.getRelationshipType();
                if (target == null || !target.contains("embeddings")) continue;
                if (!relType.contains("oleObject") && !relType.contains("package")) continue;
                String targetName = target.substring(target.lastIndexOf('/') + 1);
                relTargets.put(rel.getId(), targetName);
            }
        } catch (Exception e) {
            log.debug("No OLE embedding relationships found: {}", e.getMessage());
            return new OleMaps(relFileMap, dataMap);
        }

        if (relTargets.isEmpty()) return new OleMaps(relFileMap, dataMap);

        // 2. 遍历 OPCPackage 中所有 embedding parts（不依赖 content-type），匹配关系条目
        try {
        for (PackagePart part : doc.getPackage().getParts()) {
            String partName = part.getPartName().getName();
            if (partName == null || !partName.contains("embeddings")) continue;
            String pn = partName.substring(partName.lastIndexOf('/') + 1);
            if (pn.isEmpty()) continue;

            // 检查是否匹配任一 OLE 关系
            String matchedRelId = null;
            for (var entry : relTargets.entrySet()) {
                if (entry.getValue().equals(pn)) {
                    matchedRelId = entry.getKey();
                    break;
                }
            }
            if (matchedRelId == null) continue;

            byte[] oleBytes;
            try (InputStream in = part.getInputStream()) {
                oleBytes = StringUtils.readBytes(in);
            } catch (Exception e) {
                continue;
            }
            java.util.Map<String, byte[]> extracted = OleExtractor.extract(oleBytes);
            List<String> names = new java.util.ArrayList<>();
            if (!extracted.isEmpty()) {
                for (var ex : extracted.entrySet()) {
                    String key = ex.getKey();
                    int idx = 0;
                    while (dataMap.containsKey(key)) {
                        key = idx++ + "_" + ex.getKey();
                    }
                    dataMap.put(key, ex.getValue());
                    names.add(key);
                }
            } else if (oleBytes.length > 0) {
                String key = pn;
                int idx = 0;
                while (dataMap.containsKey(key)) {
                    key = idx++ + "_" + pn;
                }
                dataMap.put(key, oleBytes);
                names.add(key);
            }
            if (!names.isEmpty()) {
                relFileMap.put(matchedRelId, names);
            }
        }
        } catch (Exception e) {
            log.debug("Failed to list embedding parts: {}", e.getMessage());
        }
        return new OleMaps(relFileMap, dataMap);
    }

    // ==================== 嵌入部件工具 ====================

    /**
     * 查找 OPCPackage 中 /word/embeddings/ 部分的 PackagePart。
     *
     * @param pkg    OPCPackage 实例
     * @param oleOnly true=只返回 OLE 对象，false=只返回普通嵌入文件
     */
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

    /**
     * 从 PackagePart 的名称中提取文件名（去掉路径前缀）。
     * 例如 "/word/embeddings/image1.png" → "image1.png"。
     */
    private String partNameFromPart(PackagePart part) {
        String name = part.getPartName().getName();
        if (name == null) return null;
        name = name.substring(name.lastIndexOf('/') + 1);
        return name.isEmpty() ? null : name;
    }

    /** OLE 提取结果：关系映射 + 数据映射。 */
    private record OleMaps(Map<String, List<String>> relFileMap, Map<String, byte[]> dataMap) {}
}
