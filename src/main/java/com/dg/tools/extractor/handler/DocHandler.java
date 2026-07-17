package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.*;

import java.io.InputStream;

public class DocHandler implements DocumentHandler {

    @Override
    public boolean supports(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".doc");
    }

    @Override
    public ExtractionResult extract(InputStream is, String fileName) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
        ExtractionResult result = new ExtractionResult("doc", fileName);
        int position = 0;

        try (HWPFDocument doc = new HWPFDocument(is)) {
            Range range = doc.getRange();
            if (range != null) {
                for (int i = 0; i < range.numParagraphs(); i++) {
                    Paragraph para = range.getParagraph(i);
                    if (para == null) continue;
                    String text = para.text();
                    if (text != null && !text.trim().isEmpty()) {
                        result.addElement(new Element(position++, "paragraph", text.trim()));
                    }
                }
            }
        } catch (Exception e) {
            // Return empty result for unparseable .doc files
            // The file type is still recorded for traceability
        }

        return result;
    }
}
