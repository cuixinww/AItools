package com.dg.tools.extractor.model;

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
