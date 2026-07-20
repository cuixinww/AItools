package com.dg.tools.extractor.model;

import java.util.List;

/**
 * 大表信息（LargeTableInfo）。
 *
 * 当 Excel 的某区域超过「小表」阈值（行数或列数）时，不再把整张表渲染进 body.md，
 * 而是以 data_ref 形式引用，仅保留 schema（列名）+ 预览（前几行），
 * 完整数据通过 allRows 写出到 data/{sheet}.csv（必要时切片）。
 *
 * 字段说明：
 *   - sheetName：工作表名称（用作 CSV 文件名）；
 *   - position ：在解析结果中的元素序号；
 *   - schema   ：列名，以 " | " 分隔；
 *   - preview  ：预览文本（Columns 行 + 前 2 行数据）；
 *   - rowCount ：总行数；
 *   - allRows  ：全部行数据（含表头），供写完整 CSV。
 */
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
