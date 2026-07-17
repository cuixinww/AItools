package com.dg.tools.extractor.model;

import java.util.List;

public class LargeTableInfo {
    private String sheetName;
    private int position;
    private String schema;
    private String preview;
    private int rowCount;
    private List<List<String>> allRows;

    public LargeTableInfo() {}

    public LargeTableInfo(String sheetName, int position, String schema,
                          String preview, int rowCount, List<List<String>> allRows) {
        this.sheetName = sheetName;
        this.position = position;
        this.schema = schema;
        this.preview = preview;
        this.rowCount = rowCount;
        this.allRows = allRows;
    }

    public String getSheetName() { return sheetName; }
    public void setSheetName(String sheetName) { this.sheetName = sheetName; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
    public String getPreview() { return preview; }
    public void setPreview(String preview) { this.preview = preview; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public List<List<String>> getAllRows() { return allRows; }
    public void setAllRows(List<List<String>> allRows) { this.allRows = allRows; }
}
