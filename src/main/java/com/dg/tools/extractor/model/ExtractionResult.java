package com.dg.tools.extractor.model;

import java.util.ArrayList;
import java.util.List;

public class ExtractionResult {
    private String fileType;
    private String fileName;
    private List<Element> elements = new ArrayList<>();
    private List<EmbeddedFile> embeddedFiles = new ArrayList<>();
    private List<ImageFile> images = new ArrayList<>();
    private List<LargeTableInfo> largeTables = new ArrayList<>();

    public ExtractionResult() {}

    public ExtractionResult(String fileType, String fileName) {
        this.fileType = fileType;
        this.fileName = fileName;
    }

    public void addElement(Element e) { elements.add(e); }
    public void addEmbedded(EmbeddedFile e) { embeddedFiles.add(e); }
    public void addImage(ImageFile img) { images.add(img); }
    public void addLargeTable(LargeTableInfo t) { largeTables.add(t); }

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
}
