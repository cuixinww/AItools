package com.dg.tools.extractor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 完整解析结果（ExtractionResult）。
 *
 * 阶段 2 解析后产出的统一数据结构，聚合了一个文档的所有内容：
 *   - elements     ：有序的内容元素（段落 / 表格 / 图片引用 / 内嵌标记等）；
 *   - embeddedFiles：内嵌文件（原始字节，待后续递归拆包）；
 *   - images       ：图片（原始字节，供 media/ 保存与视觉模型描述）；
 *   - largeTables  ：大表信息（data_ref + 完整行，供写 CSV）；
 *   - errors       ：解析过程中收集的非致命错误信息。
 */
public class ExtractionResult {
    private String fileType;
    private String fileName;
    private List<Element> elements = new ArrayList<>();
    private List<EmbeddedFile> embeddedFiles = new ArrayList<>();
    private List<ImageFile> images = new ArrayList<>();
    private List<LargeTableInfo> largeTables = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public ExtractionResult() {}

    public ExtractionResult(String fileType, String fileName) {
        this.fileType = fileType;
        this.fileName = fileName;
    }

    public void addElement(Element e) { elements.add(e); }
    public void addEmbedded(EmbeddedFile e) { embeddedFiles.add(e); }
    public void addImage(ImageFile img) { images.add(img); }
    public void addLargeTable(LargeTableInfo t) { largeTables.add(t); }
    public void addError(String err) { errors.add(err); }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public List<Element> getElements() { return elements; }
    public void setElements(List<Element> elements) { this.elements = elements; }
    public List<EmbeddedFile> getEmbeddedFiles() { return embeddedFiles; }
    public void setEmbeddedFiles(List<EmbeddedFile> embeddedFiles) { this.embeddedFiles = embeddedFiles; }
    public List<ImageFile> getImages() { return images; }
    public void setImages(List<ImageFile> images) { this.images = images; }
    public List<LargeTableInfo> getLargeTables() { return largeTables; }
    public void setLargeTables(List<LargeTableInfo> largeTables) { this.largeTables = largeTables; }
    public List<String> getErrors() { return errors; }
}
