package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容元素模型。
 *
 * 表示文档解析后产生的一个最小内容单元，对应 body.md 中的一段结构化数据。
 * 每个 Element 由位置序号、类型标识、可选内容和可选元信息组成，
 * 用于上层构建 Markdown 输出、chunks 分块及 AI 检核层的数据消费。
 *
 * <p>典型类型包括：
 * <ul>
 *   <li>paragraph — 正文段落（可能含 headingLevel 标记为标题）</li>
 *   <li>table — Markdown 渲染的表格文本</li>
 *   <li>image — 图片引用，metadata 中包含 media/ 路径</li>
 *   <li>embed — 内嵌文件引用，metadata 中包含文件名</li>
 *   <li>data_ref — 大表引用，metadata 中包含 CSV schema 和行数</li>
 *   <li>header / footer — 页眉页脚内容</li>
 *   <li>sheet_header — Excel Sheet 名称标签</li>
 * </ul>
 *
 * @see ExtractionResult
 * @see StoreWriter#writeBody
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Element {

    /** 元素在文档中的位置序号（从 0 起递增，同一文档内唯一且有序）。 */
    private int position;

    /** 元素类型标识，如 "paragraph"、"table"、"image"、"data_ref" 等。 */
    private String type;

    /** 元素正文内容，对 paragraph 为文本，对 table 为 Markdown 表格，可为 null。 */
    private String content;

    /** 附加元信息，单行字符串，如 embed 的 "file: xxx"、data_ref 的 schema 描述，可为 null。 */
    private String metadata;

    /** 标题级别，仅当 type 为 paragraph 且该段落是标题时有效，范围 1-9，null 表示非标题。 */
    private Integer headingLevel;

    /**
     * 简化的构造函数，用于创建不含元信息的普通内容元素。
     *
     * @param position 位置序号
     * @param type     元素类型
     * @param content  元素正文内容
     */
    public Element(int position, String type, String content) {
        this(position, type, content, null);
    }

    /**
     * 简化的构造函数，用于创建带元信息的元素（如内嵌文件引用）。
     *
     * @param position 位置序号
     * @param type     元素类型
     * @param content  元素正文内容
     * @param metadata 元信息字符串
     */
    public Element(int position, String type, String content, String metadata) {
        this.position = position;
        this.type = type;
        this.content = content;
        this.metadata = metadata;
    }
}
