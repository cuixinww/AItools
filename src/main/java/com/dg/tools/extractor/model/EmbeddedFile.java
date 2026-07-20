package com.dg.tools.extractor.model;

/**
 * 内嵌文件（EmbeddedFile）。
 *
 * 表示从文档中拆出的一个附件 / 内嵌对象（如嵌入的 Excel、PDF、OLE 解出的内部文件等）。
 * 携带原始字节，供阶段 1 递归拆包或阶段 2 解析使用。
 *
 * 字段说明：
 *   - fileName：内嵌文件名（OLE 场景可能带路径前缀以消歧）；
 *   - position：在父文档中出现的位置序号（用于父上下文回溯）；
 *   - data    ：文件原始字节内容。
 */
public class EmbeddedFile {
    private String fileName;
    private int position;
    private byte[] data;

    public EmbeddedFile() {}

    public EmbeddedFile(String fileName, int position, byte[] data) {
        this.fileName = fileName;
        this.position = position;
        this.data = data;
    }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
}
