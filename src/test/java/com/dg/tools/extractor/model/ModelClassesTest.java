package com.dg.tools.extractor.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ModelClassesTest {

    @Test
    void elementGettersSetters() {
        Element e = new Element();
        e.setPosition(7);
        e.setType("paragraph");
        e.setContent("正文");
        e.setMetadata("meta");
        assertThat(e.getPosition()).isEqualTo(7);
        assertThat(e.getType()).isEqualTo("paragraph");
        assertThat(e.getContent()).isEqualTo("正文");
        assertThat(e.getMetadata()).isEqualTo("meta");

        Element e2 = new Element(1, "table", "md", "m");
        assertThat(e2.getPosition()).isEqualTo(1);
        assertThat(e2.getMetadata()).isEqualTo("m");
    }

    @Test
    void extractionResultAdders() {
        ExtractionResult r = new ExtractionResult();
        r.setFileType("docx");
        r.setFileName("a.docx");
        r.addElement(new Element(0, "paragraph", "x"));
        r.addEmbedded(new EmbeddedFile("e.xlsx", 1, new byte[]{1}));
        r.addImage(new ImageFile("i.png", 2, new byte[]{2}, "png"));
        r.addLargeTable(new LargeTableInfo("t", 3, "s", "p", 4, List.of()));
        r.addError("err");

        assertThat(r.getFileType()).isEqualTo("docx");
        assertThat(r.getElements()).hasSize(1);
        assertThat(r.getEmbeddedFiles()).hasSize(1);
        assertThat(r.getImages()).hasSize(1);
        assertThat(r.getLargeTables()).hasSize(1);
        assertThat(r.getErrors()).contains("err");

        ExtractionResult r2 = new ExtractionResult("pdf", "b.pdf");
        assertThat(r2.getFileType()).isEqualTo("pdf");
        assertThat(r2.getFileName()).isEqualTo("b.pdf");
    }

    @Test
    void unpackResultAdders() {
        UnpackResult u = new UnpackResult();
        u.setFileType("zip");
        u.setFileName("a.zip");
        u.addEmbedded(new EmbeddedFile("e", 0, new byte[]{1}));
        u.addImage(new ImageFile("i", 1, new byte[]{2}, "png"));
        u.addError("x");

        assertThat(u.getFileType()).isEqualTo("zip");
        assertThat(u.getEmbeddedFiles()).hasSize(1);
        assertThat(u.getImages()).hasSize(1);
        assertThat(u.getErrors()).contains("x");

        UnpackResult u2 = new UnpackResult("image", "p.png");
        assertThat(u2.getFileType()).isEqualTo("image");
        assertThat(u2.getImages()).isEmpty();
    }

    @Test
    void embeddedFileAccessors() {
        EmbeddedFile f = new EmbeddedFile();
        f.setFileName("a.xlsx");
        f.setPosition(3);
        f.setData(new byte[]{9});
        assertThat(f.getFileName()).isEqualTo("a.xlsx");
        assertThat(f.getPosition()).isEqualTo(3);
        assertThat(f.getData()).containsExactly(9);

        EmbeddedFile f2 = new EmbeddedFile("b", 1, new byte[]{1, 2});
        assertThat(f2.getFileName()).isEqualTo("b");
        assertThat(f2.getData()).hasSize(2);
    }

    @Test
    void imageFileAccessors() {
        ImageFile img = new ImageFile();
        img.setFileName("x.png");
        img.setPosition(2);
        img.setData(new byte[]{5});
        img.setFormat("png");
        assertThat(img.getFileName()).isEqualTo("x.png");
        assertThat(img.getFormat()).isEqualTo("png");

        ImageFile img2 = new ImageFile("y.jpg", 4, new byte[]{1}, "jpg");
        assertThat(img2.getPosition()).isEqualTo(4);
        assertThat(img2.getFormat()).isEqualTo("jpg");
    }

    @Test
    void largeTableInfoAccessors() {
        List<List<String>> rows = List.of(List.of("a", "b"));
        LargeTableInfo t = new LargeTableInfo();
        t.setSheetName("S");
        t.setPosition(1);
        t.setSchema("a | b");
        t.setPreview("preview");
        t.setRowCount(10);
        t.setAllRows(rows);

        assertThat(t.getSheetName()).isEqualTo("S");
        assertThat(t.getRowCount()).isEqualTo(10);
        assertThat(t.getAllRows()).hasSize(1);

        LargeTableInfo t2 = new LargeTableInfo("S2", 2, "c | d", "p", 3, rows);
        assertThat(t2.getSchema()).isEqualTo("c | d");
    }
}
