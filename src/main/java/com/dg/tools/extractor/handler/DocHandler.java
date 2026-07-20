package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.*;

import java.io.InputStream;

/**
 * DOC 文档处理器（DocHandler）。
 *
 * 基于 Apache POI (HWPF) 解析 Word 97-2003（.doc，OLE2 二进制格式）文档。
 * 当前实现提取段落与表格单元格中的文本，按出现顺序生成 paragraph / table_cell 元素。
 *
 * 说明：HWPF 对复杂 .doc 格式支持有限，且本项目测试夹具构造的最小 .doc
 * 可能无法被 HWPF 完整解析；对真实 .doc 文件通常可正常工作。
 */
public class DocHandler implements DocumentHandler {

    /**
     * 是否支持该文件：仅匹配 .doc（不含 .docx）。
     */
    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".doc");
    }

    /**
     * 阶段 2 解析：遍历文档文本范围（Range），逐段落抽取文本。
     * 处于表格内的段落标记为 table_cell 类型，其余为 paragraph。
     */
    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("doc", fileName);
        int position = 0;

        try (HWPFDocument doc = new HWPFDocument(is)) {
            Range range = doc.getRange();
            if (range == null) return result;

            for (int i = 0; i < range.numParagraphs(); i++) {
                Paragraph para = range.getParagraph(i);
                if (para == null) continue;

                String text = para.text();
                if (text == null) continue;
                String trimmed = text.trim();
                if (trimmed.isEmpty()) continue;

                // 根据段落是否位于表格内决定元素类型
                String type = para.isInTable() ? "table_cell" : "paragraph";
                result.addElement(new Element(position++, type, trimmed));
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse doc: " + fileName, e);
        }

        return result;
    }
}
