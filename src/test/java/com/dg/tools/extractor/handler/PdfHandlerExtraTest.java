package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class PdfHandlerExtraTest {

    // Uncovered: doUnpack exception path, paragraph-with-empty-check,
    // readImageBytes fallback (jpg raw stream), image decode error path
    @Test
    void unpackPdfErrorPath() throws Exception {
        PdfHandler h = new PdfHandler();
        byte[] invalid = "not a pdf".getBytes();
        ExtractionResult r = h.unpack(new ByteArrayInputStream(invalid), "bad.pdf");
        assertThat(r.getErrors()).isNotEmpty();
    }

    @Test
    void extractPdfMultiParagraph() throws Exception {
        PdfHandler h = new PdfHandler();
        // createSimplePdf with multiple lines produces paragraphs separated by blank lines
        byte[] pdf = TestFileFactory.createSimplePdf("Para1", "", "", "Para2");
        ExtractionResult r = h.extract(new ByteArrayInputStream(pdf), "multi.pdf");
        List<Element> paras = r.getElements().stream().filter(e -> "paragraph".equals(e.getType())).toList();
        // Each non-blank text block becomes one paragraph element
        assertThat(paras).isNotEmpty();
    }

    @Test
    void extractPdfParagraphsWithEmptyGaps() throws Exception {
        // Build PDF with multiple non-empty blocks
        PdfHandler h = new PdfHandler();
        byte[] pdf = TestFileFactory.createSimplePdf("Block A", "", "", "Block B");
        ExtractionResult r = h.extract(new ByteArrayInputStream(pdf), "blocks.pdf");
        List<Element> paras = r.getElements().stream().filter(e -> "paragraph".equals(e.getType())).toList();
        assertThat(paras).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    void extractPdfSingleLineNoBlanks() throws Exception {
        PdfHandler h = new PdfHandler();
        byte[] pdf = TestFileFactory.createSimplePdf("JustOneLine");
        ExtractionResult r = h.extract(new ByteArrayInputStream(pdf), "single.pdf");
        List<Element> paras = r.getElements().stream().filter(e -> "paragraph".equals(e.getType())).toList();
        assertThat(paras).hasSize(1);
        assertThat(paras.get(0).getContent()).contains("JustOneLine");
    }

    @Test
    void extractPdfEmptyNoParagraphs() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("", "");
        PdfHandler h = new PdfHandler();
        ExtractionResult r = h.extract(new ByteArrayInputStream(pdf), "empty.pdf");
        assertThat(r.getElements()).isEmpty();
    }

    private byte[] createPdfWithMultipleTextBlocks() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            // Simpler: just rely on SimplePdfFactory with multiple lines
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
