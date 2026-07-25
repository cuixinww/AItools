package com.dg.tools.extractor.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 图片文件（ImageFile）。
 *
 * 表示从文档中抽出的图片（原始字节），同时记录其在父文档中的位置与格式。
 * 原始字节会被写入 media/ 目录；格式用于决定文件扩展名。
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ImageFile extends EmbeddedFile {
    private String format;

    public ImageFile(String fileName, int position, byte[] data, String format) {
        super(fileName, position, data);
        this.format = format;
    }
}
