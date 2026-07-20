package com.dg.tools.extractor.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 拆包结果（UnpackResult）。
 *
 * 阶段 1（UNPACK）的轻量产物：只携带内嵌文件与图片的原始字节，
 * 不做任何段落 / 表格文本解析（那些工作在阶段 2 才进行）。
 *
 * 用于把文件快速展开到磁盘并递归处理内嵌文件，同时把内存占用控制在最小。
 */
public class UnpackResult {
    private String fileType;
    private String fileName;
    private final List<EmbeddedFile> embeddedFiles = new ArrayList<>();
    private final List<ImageFile> images = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public UnpackResult() {}

    public UnpackResult(String fileType, String fileName) {
        this.fileType = fileType;
        this.fileName = fileName;
    }

    public void addEmbedded(EmbeddedFile e) { embeddedFiles.add(e); }
    public void addImage(ImageFile img) { images.add(img); }
    public void addError(String err) { errors.add(err); }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public List<EmbeddedFile> getEmbeddedFiles() { return embeddedFiles; }
    public List<ImageFile> getImages() { return images; }
    public List<String> getErrors() { return errors; }
}
