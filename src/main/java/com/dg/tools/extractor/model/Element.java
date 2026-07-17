package com.dg.tools.extractor.model;

public class Element {
    private int position;
    private String type;
    private String content;
    private String metadata;

    public Element() {}

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

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
