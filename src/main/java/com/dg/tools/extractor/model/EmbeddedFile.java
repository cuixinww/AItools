package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * 内嵌文件描述符。
 *
 * 从文档中拆出的附件或 OLE 解出的内部文件，携带原始字节用于递归拆包或再次解析。
 * OLE 场景下 fileName 可能包含路径前缀（如 "WordDocument/inner.xlsx"），
 * 用于区分来自不同内嵌对象的同名文件，避免目录冲突。
 *
 * @see OleExtractor#extract
 * @see EmbeddedFile
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
@NoArgsConstructor
@AllArgsConstructor
public class EmbeddedFile {

    /** 内嵌文件名。普通附件为纯文件名；OLE 解出时可能带路径前缀（用于消歧）。 */
    private String fileName;

    /** 该文件在父文档中的出现位置序号，用于定位和溯源。 */
    private int position;

    /** 文件的原始字节内容，由上层 Handler 提取后填入。 */
    private byte[] data;

    /** 返回字节数组的防御性复制。 */
    public byte[] getData() {
        return data == null ? null : data.clone();
    }

    /** 设置字节数组（内部存储传入的引用，不额外复制）。 */
    public void setData(byte[] data) {
        this.data = data;
    }
}
