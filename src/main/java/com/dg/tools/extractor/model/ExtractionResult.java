package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整解析结果（ExtractionResult）— Phase 1 (UNPACK) 与 Phase 2 (PARSE) 统一数据结构。
 *
 * Phase 1 时 elements / largeTables 为空列表；Phase 2 时回填完整内容。
 * 聚合了一个文档的所有内容：
 *   - elements     ：有序的内容元素（段落 / 表格 / 图片引用 / 内嵌标记等）；
 *   - embeddedFiles：内嵌文件（原始字节，待后续递归拆包）；
 *   - images       ：图片（原始字节，供 media/ 保存与视觉模型描述）；
 *   - largeTables  ：大表信息（data_ref + 完整行，供写 CSV）；
 *   - errors       ：解析过程中收集的非致命错误信息。
 */
@Data
@AllArgsConstructor
public class ExtractionResult {
    private String fileType;
    private String fileName;
    private List<Element> elements;
    private List<EmbeddedFile> embeddedFiles;
    private List<ImageFile> images;
    private List<LargeTableInfo> largeTables;
    private List<String> errors;

    public static ExtractionResult of(String fileType, String fileName) {
        return new ExtractionResult(fileType, fileName,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
    }

    public void addElement(Element e) { if (elements == null) elements = new ArrayList<>(); elements.add(e); }
    public void addEmbedded(EmbeddedFile e) { if (embeddedFiles == null) embeddedFiles = new ArrayList<>(); embeddedFiles.add(e); }
    public void addImage(ImageFile img) { if (images == null) images = new ArrayList<>(); images.add(img); }
    public void addLargeTable(LargeTableInfo t) { if (largeTables == null) largeTables = new ArrayList<>(); largeTables.add(t); }
    public void addError(String err) { if (errors == null) errors = new ArrayList<>(); errors.add(err); }

    public void setElements(List<Element> elements) { this.elements.clear(); if (elements != null) this.elements.addAll(elements); }
    public void setEmbeddedFiles(List<EmbeddedFile> embeddedFiles) { this.embeddedFiles.clear(); if (embeddedFiles != null) this.embeddedFiles.addAll(embeddedFiles); }
    public void setImages(List<ImageFile> images) { this.images.clear(); if (images != null) this.images.addAll(images); }
}
