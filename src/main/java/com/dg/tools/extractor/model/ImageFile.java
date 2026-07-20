package com.dg.tools.extractor.model;

/**
 * 图片文件（ImageFile）。
 *
 * 表示从文档中抽出的图片（原始字节），同时记录其在父文档中的位置与格式。
 * 原始字节会被写入 media/ 目录；格式用于决定文件扩展名。
 *
 * 字段说明：
 *   - fileName：图片文件名（如 image_0.png）；
 *   - position：在父文档中出现的位置序号；
 *   - data    ：图片原始字节；
 *   - format  ：图片格式（png / jpg / ...），用于命名与回写。
 */
public class ImageFile {
    private String fileName;
    private int position;
    private byte[] data;
    private String format;

    public ImageFile() {}

    public ImageFile(String fileName, int position, byte[] data, String format) {
        this.fileName = fileName;
        this.position = position;
        this.data = data;
        this.format = format;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
}
