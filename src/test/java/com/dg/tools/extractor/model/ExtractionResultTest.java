package com.dg.tools.extractor.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class ExtractionResultTest {

    @Test
    void ofFactoryCreatesWithEmptyLists() {
        ExtractionResult r = ExtractionResult.of("docx", "test.docx");
        assertThat(r.getFileType()).isEqualTo("docx");
        assertThat(r.getFileName()).isEqualTo("test.docx");
        assertThat(r.getElements()).isEmpty();
        assertThat(r.getEmbeddedFiles()).isEmpty();
        assertThat(r.getImages()).isEmpty();
        assertThat(r.getLargeTables()).isEmpty();
        assertThat(r.getErrors()).isEmpty();
    }

    @Test
    void allArgsConstructor() {
        List<Element> elements = new ArrayList<>();
        elements.add(new Element(0, "p", "hello"));
        ExtractionResult r = new ExtractionResult("docx", "test.docx",
                elements, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertThat(r.getElements()).hasSize(1);
    }

    @Test
    void addElement() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addElement(new Element(0, "p", "test"));
        assertThat(r.getElements()).hasSize(1);
    }

    @Test
    void addEmbedded() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addEmbedded(new EmbeddedFile("f.bin", 0, new byte[]{1}));
        assertThat(r.getEmbeddedFiles()).hasSize(1);
    }

    @Test
    void addImage() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addImage(new ImageFile("i.png", 0, new byte[]{1}, "png"));
        assertThat(r.getImages()).hasSize(1);
    }

    @Test
    void addLargeTable() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addLargeTable(new LargeTableInfo("s", 0, "c1", "pre", 10, List.of()));
        assertThat(r.getLargeTables()).hasSize(1);
    }

    @Test
    void addError() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addError("something went wrong");
        assertThat(r.getErrors()).hasSize(1);
    }

    @Test
    void setElementsReplacesContent() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addElement(new Element(0, "p", "old"));
        r.setElements(List.of(new Element(1, "p", "new")));
        assertThat(r.getElements()).hasSize(1);
        assertThat(r.getElements().get(0).getContent()).isEqualTo("new");
    }

    @Test
    void setElementsWithNullClears() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addElement(new Element(0, "p", "old"));
        r.setElements(null);
        assertThat(r.getElements()).isEmpty();
    }

    @Test
    void setEmbeddedFilesReplacesContent() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addEmbedded(new EmbeddedFile("old.bin", 0, new byte[]{1}));
        r.setEmbeddedFiles(List.of(new EmbeddedFile("new.bin", 1, new byte[]{2})));
        assertThat(r.getEmbeddedFiles()).hasSize(1);
        assertThat(r.getEmbeddedFiles().get(0).getFileName()).isEqualTo("new.bin");
    }

    @Test
    void setEmbeddedFilesWithNullClears() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addEmbedded(new EmbeddedFile("f.bin", 0, new byte[]{1}));
        r.setEmbeddedFiles(null);
        assertThat(r.getEmbeddedFiles()).isEmpty();
    }

    @Test
    void setImagesReplacesContent() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addImage(new ImageFile("old.png", 0, new byte[]{1}, "png"));
        r.setImages(List.of(new ImageFile("new.png", 1, new byte[]{2}, "png")));
        assertThat(r.getImages()).hasSize(1);
        assertThat(r.getImages().get(0).getFileName()).isEqualTo("new.png");
    }

    @Test
    void setImagesWithNullClears() {
        ExtractionResult r = ExtractionResult.of("type", "name");
        r.addImage(new ImageFile("i.png", 0, new byte[]{1}, "png"));
        r.setImages(null);
        assertThat(r.getImages()).isEmpty();
    }

    @Test
    void fileTypeGetterSetter() {
        ExtractionResult r = ExtractionResult.of("old", "n");
        r.setFileType("new");
        assertThat(r.getFileType()).isEqualTo("new");
    }

    @Test
    void fileNameGetterSetter() {
        ExtractionResult r = ExtractionResult.of("t", "old");
        r.setFileName("new");
        assertThat(r.getFileName()).isEqualTo("new");
    }

    @Test
    void multipleErrorsAccumulate() {
        ExtractionResult r = ExtractionResult.of("t", "n");
        r.addError("err1");
        r.addError("err2");
        assertThat(r.getErrors()).hasSize(2);
    }
}
