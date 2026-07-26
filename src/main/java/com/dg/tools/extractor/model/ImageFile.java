package com.dg.tools.extractor.model;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 图片文件描述符。
 *
 * 继承 EmbeddedFile，额外记录图片格式（png/jpg/gif 等），
 * 用于将原始字节写入 media/ 目录并决定文件扩展名。
 * Phase 1.5 IMAGE 阶段会将此字节数据传入 ImageDescriber 进行视觉理解。
 *
 * @see ImageDescriber
 * @see StoreWriter#writeMedia
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class ImageFile extends EmbeddedFile {

    /** 图片格式标识，如 "png"、"jpg"、"gif"、"tiff" 等，用于生成文件名后缀。 */
    private String format;

    /**
     * 创建图片文件描述符。
     *
     * @param fileName 图片文件名
     * @param position 在父文档中的位置序号
     * @param data     图片原始字节
     * @param format   图片格式标识
     */
    public ImageFile(String fileName, int position, byte[] data, String format) {
        super(fileName, position, data);
        this.format = format;
    }
}
