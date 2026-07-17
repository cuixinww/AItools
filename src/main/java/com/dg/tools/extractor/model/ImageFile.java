package com.dg.tools.extractor.model;

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
