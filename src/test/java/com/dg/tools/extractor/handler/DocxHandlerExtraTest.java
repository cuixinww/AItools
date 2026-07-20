package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTc;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.*;

class DocxHandlerExtraTest {

    private final DocxHandler handler = new DocxHandler();

    @Test
    void shouldRenderTableWithGridSpan() throws Exception {
        // 构造一个含 gridSpan 合并列（col1 跨2列）的表格
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(2, 3);
            table.getRow(0).getCell(0).setText("A");
            table.getRow(0).getCell(1).setText("B");
            table.getRow(0).getCell(2).setText("C");
            table.getRow(1).getCell(0).setText("1");
            // 合并行1的列1、列2
            table.getRow(1).getCell(1).setText("BC");
            CTTc ctTc = table.getRow(1).getCell(1).getCTTc();
            ctTc.addNewTcPr().addNewGridSpan().setVal(java.math.BigInteger.valueOf(2));

            byte[] bytes;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { doc.write(baos); bytes = baos.toByteArray(); }

            ExtractionResult result = handler.extract(TestFileFactory.toInputStream(bytes), "grid.docx");
            Element tableEl = result.getElements().stream()
                    .filter(e -> "table".equals(e.getType())).findFirst().orElseThrow();
            // 合并后应铺满为 3 列：| A | B | C | / | 1 | BC | BC(空) |
            assertThat(tableEl.getContent()).contains("| A | B | C |");
        }
    }

    @Test
    void shouldExtractHeaderFooterTables() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("正文");

            // 页眉里放一个表格
            XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
            XWPFTable ht = header.createTable(1, 2);
            ht.getRow(0).getCell(0).setText("H1");
            ht.getRow(0).getCell(1).setText("H2");

            byte[] bytes;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { doc.write(baos); bytes = baos.toByteArray(); }

            ExtractionResult result = handler.extract(TestFileFactory.toInputStream(bytes), "hf.docx");
            // 页眉表格应作为 header 类型元素
            assertThat(result.getElements().stream().anyMatch(e -> "header".equals(e.getType()))).isTrue();
        }
    }

    @Test
    void shouldRecordEmbedElementForEmbeddedFile() throws Exception {
        byte[] xlsx = TestFileFactory.createSimpleExcel("S1", new String[]{"A"});
        byte[] docx = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("data.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx));
        ExtractionResult result = handler.extract(TestFileFactory.toInputStream(docx), "p.docx");
        assertThat(result.getElements().stream().anyMatch(e -> "embed".equals(e.getType()))).isTrue();
    }

    @Test
    void shouldExtractImageInsideTableCell() throws Exception {
        try (XWPFDocument doc = new XWPFDocument()) {
            XWPFTable table = doc.createTable(1, 1);
            XWPFTable docs = table;
            var cell = table.getRow(0).getCell(0);
            var para = cell.getParagraphs().get(0);
            var run = para.createRun();
            try (java.io.InputStream png = new java.io.ByteArrayInputStream(TestFileFactory.createMinimalPng())) {
                run.addPicture(png, XWPFDocument.PICTURE_TYPE_PNG, "cell.png",
                        org.apache.poi.util.Units.toEMU(50), org.apache.poi.util.Units.toEMU(50));
            }
            byte[] bytes;
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) { doc.write(baos); bytes = baos.toByteArray(); }

            ExtractionResult result = handler.extract(TestFileFactory.toInputStream(bytes), "cellimg.docx");
            assertThat(result.getImages()).isNotEmpty();
        }
    }

    @Test
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "a.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "a.docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotSupportDoc() {
        assertThat(handler.supports("a.doc")).isFalse();
        assertThat(handler.supports("a.DOCX")).isTrue();
    }
}
