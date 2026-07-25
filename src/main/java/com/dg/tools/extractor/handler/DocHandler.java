package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * DOC 文档处理器（DocHandler）。
 *
 * 基于 Apache POI (HWPF) 解析 Word 97-2003（.doc，OLE2 二进制格式）文档。
 * 当前实现提取段落与表格单元格中的文本，按出现顺序生成 paragraph / table_cell 元素。
 *
 * 对应 SPEC §9 路由表：.doc → DocHandler。
 */
@Component
@Slf4j
public class DocHandler extends AbstractHandler {

    public DocHandler() {
        super();
    }

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".doc");
    }

    @Override
    protected ExtractionResult doExtract(InputStream is, String fileName) throws Exception {
        ExtractionResult result = ExtractionResult.of("doc", fileName);
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

                String type = para.isInTable() ? "table_cell" : "paragraph";
                result.addElement(new Element(position++, type, trimmed));
            }

        } catch (Exception e) {
            log.error("Failed to parse doc: {}", fileName, e);
            result.addError("parse error: " + e.getMessage());
        }

        return result;
    }
}
