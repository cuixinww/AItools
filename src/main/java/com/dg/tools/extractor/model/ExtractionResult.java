package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 提取结果聚合体。
 *
 * 贯穿 Phase 1（UNPACK 拆包）和 Phase 2（PARSE 解析）的统一数据结构，
 * 由每个 Handler 的 doExtract/doUnpack 方法返回，封装单个文档的完整解析产物。
 * <ul>
 *   <li>Phase 1: elements 和 largeTables 为空，仅填充 embeddedFiles 和 images</li>
 *   <li>Phase 2: elements 回填全部内容，largeTables 包含大表引用</li>
 *   <li>任意阶段: errors 收集非致命异常，不影响整体成功判定</li>
 * </ul>
 *
 * <p>线程安全注意：此类不是线程安全的，但每个 Handler 实例在单次 extract/unpack 调用中
 * 创建独立的 ExtractionResult 实例，不存在跨线程共享可变状态的 risk。</p>
 *
 * @see AbstractHandler#doExtract
 * @see AbstractHandler#doUnpack
 * @see RecursiveExtractor.Session
 */
@Data
@AllArgsConstructor
public class ExtractionResult {

    /** 文件类型标识，如 "docx"、"xlsx"、"pdf"、"zip" 等。 */
    private String fileType;

    /** 原始文件名，用于日志记录和 manifest.json 溯源。 */
    private String fileName;

    /** 有序内容元素列表，Phase 1 为空，Phase 2 回填。 */
    private List<Element> elements;

    /** 内嵌文件列表，来自各种 Handler 的 unpack 阶段。 */
    private List<EmbeddedFile> embeddedFiles;

    /** 提取的图片列表，包含原始字节和格式信息。 */
    private List<ImageFile> images;

    /** 大表信息列表，超过阈值时将表数据分离到 data/ 目录。 */
    private List<LargeTableInfo> largeTables;

    /** 解析过程中的非致命错误列表，不影响结果判定但需记录。 */
    private List<String> errors;

    /**
     * 工厂方法：创建一个指定文件类型的空 ExtractionResult。
     * 所有 List 字段初始化为空列表而非 null，确保后续直接遍历不会 NPE。
     *
     * @param fileType 文件类型标识
     * @param fileName 原始文件名
     * @return 初始化好的 ExtractionResult 实例
     */
    public static ExtractionResult of(String fileType, String fileName) {
        return new ExtractionResult(fileType, fileName,
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                new ArrayList<>(), new ArrayList<>());
    }

    /** 向结果中添加一个内容元素。元素按出现顺序追加到列表末尾。 */
    public void addElement(Element e) { if (elements == null) elements = new ArrayList<>(); elements.add(e); }

    /** 向结果中添加一个内嵌文件。用于 zip/docx/doc 等 Handler 拆出的附件。 */
    public void addEmbedded(EmbeddedFile e) { if (embeddedFiles == null) embeddedFiles = new ArrayList<>(); embeddedFiles.add(e); }

    /** 向结果中添加一张图片。包含原始字节、文件名、位置和格式。 */
    public void addImage(ImageFile img) { if (images == null) images = new ArrayList<>(); images.add(img); }

    /** 向结果中添加一个大表信息。Excel 中大表不内联 body.md，而是写入 data/ 目录。 */
    public void addLargeTable(LargeTableInfo t) { if (largeTables == null) largeTables = new ArrayList<>(); largeTables.add(t); }

    /** 记录一条非致命错误信息。错误不会中断提取流程，但会在 manifest.json 中体现。 */
    public void addError(String err) { if (errors == null) errors = new ArrayList<>(); errors.add(err); }

    // ==================== Setter 扩展（Lombok @Data 未生成） ====================

    /** 替换全部内容为新列表。先清空当前元素，再addAll新数据（允许 null 安全）。 */
    public void setElements(List<Element> elements) { this.elements.clear(); if (elements != null) this.elements.addAll(elements); }

    /** 替换内嵌文件列表。先清空当前列表，再addAll新数据（允许 null 安全）。 */
    public void setEmbeddedFiles(List<EmbeddedFile> embeddedFiles) { this.embeddedFiles.clear(); if (embeddedFiles != null) this.embeddedFiles.addAll(embeddedFiles); }

    /** 替换图片列表。先清空当前列表，再addAll新数据（允许 null 安全）。 */
    public void setImages(List<ImageFile> images) { this.images.clear(); if (images != null) this.images.addAll(images); }
}
