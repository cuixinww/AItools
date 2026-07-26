package com.dg.tools.extractor.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 大表信息模型。
 *
 * 当 Excel 区域超过「小表」阈值（行数 >50 或列数 >10）时，不再将整表内联到 body.md，
 * 而是以 data_ref 形式引用，仅保留 schema（列名）+ 预览文本（前几行），
 * 完整数据通过 allRows 字段写出到 data/{sheet}.csv（必要时由 CsvSlicer 切片）。
 *
 * <p>内存管理：StoreWriter.writeLargeTables 写出 CSV 后会调用 clearRows() 释放 allRows 引用，
 * 允许 GC 回收占用大量堆内存的二维列表数据。</p>
 *
 * @see ExcelHandler
 * @see StoreWriter#writeLargeTables
 * @see CsvSlicer
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LargeTableInfo {

    /** 工作表名称，用作 CSV 文件名前缀。 */
    private String sheetName;

    /** 该元素在 ExtractionResult 中的位置序号，与 body.md 中的 # POS 对应。 */
    private int position;

    /** 列名 schema，各列以 " | " 分隔，如 "姓名 | 年龄 | 部门"。 */
    private String schema;

    /** 预览文本，包含 Columns 行描述和前 2 行数据摘要，用于 AI 检核层快速理解表结构。 */
    private String preview;

    /** 总行数（含表头行），用于 index.json 统计和 CSV 切片计算。 */
    private int rowCount;

    /** 全部行数据（含表头），供 writeLargeTables 逐行写出 CSV。写出后应调用 clearRows() 释放。 */
    private List<List<String>> allRows;

    /**
     * CSV 写出后调用，释放 allRows 引用允许 GC 回收。
     * 此方法在 StoreWriter.writeLargeTables 中每张大表调用一次。
     */
    public void clearRows() {
        this.allRows = null;
    }
}
