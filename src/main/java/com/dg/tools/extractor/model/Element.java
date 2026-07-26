package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 内容元素（Element）。
 *
 * 表示解析结果中的一个最小内容单元：段落、表格、图片引用、内嵌文件标记、页眉页脚等。
 * 在 body.md 中对应一行 "# POS: N | TYPE: xxx | metadata" 头 + 其下正文内容。
 *
 * 字段说明：
 *   - position    ：元素序号（在同一文档内相对有序，用于 AI 层定位与父上下文回溯）；
 *   - type        ：元素类型（paragraph / table / image / embed / header / footer / sheet_header / data_ref 等）；
 *   - content     ：元素正文（Markdown 文本、表格等），可为空；
 *   - metadata    ：附加元信息（单行字符串，如 embed 的 "file: xxx"），可为空；
 *   - headingLevel：标题级别（null=非标题，1-6=Heading 1-6），供标题感知分块使用。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Element {
    private int position;
    private String type;
    private String content;
    private String metadata;
    private Integer headingLevel;

    public Element(int position, String type, String content) {
        this.position = position;
        this.type = type;
        this.content = content;
    }

    public Element(int position, String type, String content, String metadata) {
        this.position = position;
        this.type = type;
        this.content = content;
        this.metadata = metadata;
    }
}
