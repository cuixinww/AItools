package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.handler.DocxHandler;
import com.dg.tools.extractor.model.ExtractionResult;
import org.apache.poi.util.Units;
import org.apache.poi.wp.usermodel.HeaderFooterType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

class DocxHandlerTest {

    private final DocxHandler handler = new DocxHandler();

    @Test
    void shouldSupportDocx() {
        assertThat(handler.supports("test.docx")).isTrue();
        assertThat(handler.supports("test.docm")).isTrue();
        assertThat(handler.supports("test.doc")).isFalse();
        assertThat(handler.supports(null)).isFalse();
    }

    @Test
    void shouldHandleNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "test.docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "test.docx"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldHandleInvalidDocxGracefully() throws Exception {
        byte[] invalidData = "not a valid docx file".getBytes();
        ExtractionResult result = handler.extract(new ByteArrayInputStream(invalidData), "bad.docx");
        assertThat(result).isNotNull();
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void shouldParseSimpleDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createSimpleDocx("Hello", "World");
        ExtractionResult result = handler.extract(new ByteArrayInputStream(docxBytes), "test.docx");
        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("docx");
    }

    @Test
    void shouldUnpackSimpleDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createSimpleDocx("Hello");
        ExtractionResult result = handler.unpack(new ByteArrayInputStream(docxBytes), "test.docx");
        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("docx");
    }

    @Test
    void shouldExtractEmbeddedFilesFromDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createDocxWithEmbedded(
                new TestFileFactory.EmbeddedEntry("note.txt", "text/plain",
                        "embedded payload".getBytes(StandardCharsets.UTF_8))
        );

        ExtractionResult result = handler.extract(new ByteArrayInputStream(docxBytes), "embedded.docx");

        assertThat(result.getEmbeddedFiles()).isNotEmpty();
        assertThat(result.getElements()).anyMatch(element -> element.getType().equals("embed"));
    }

    @Test
    void shouldExtractOleEmbeddingsFromDocx() throws Exception {
        byte[] docxBytes = TestFileFactory.createDocxWithOleEmbedding();

        ExtractionResult result = handler.extract(new ByteArrayInputStream(docxBytes), "ole.docx");

        assertThat(result.getEmbeddedFiles()).isNotEmpty();
        assertThat(result.getElements()).anyMatch(element -> element.getType().equals("embed"));
    }



    private byte[] buildDocxWithHeadersFootersOnly() throws Exception {
        XWPFDocument doc = new XWPFDocument();

        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        header.createParagraph().createRun().setText("Header text");

        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        footer.createParagraph().createRun().setText("Footer text");

        doc.createParagraph().createRun().setText("Body text");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void shouldUnpackHeaderFooterImages() throws Exception {
        byte[] docxBytes = createDocxWithHeaderFooterAndImage();

        ExtractionResult unpacked = handler.unpack(new ByteArrayInputStream(docxBytes), "hfimg.docx");

        assertThat(unpacked.getImages()).isNotEmpty();
    }

    private byte[] createDocxWithHeaderFooterAndImage() throws Exception {
        XWPFDocument doc = new XWPFDocument();

        XWPFHeader header = doc.createHeader(HeaderFooterType.DEFAULT);
        header.createParagraph().createRun().setText("Header text");
        try (InputStream png = new ByteArrayInputStream(TestFileFactory.createMinimalPng())) {
            header.getParagraphArray(0).createRun().addPicture(
                    png,
                    XWPFDocument.PICTURE_TYPE_PNG,
                    "header.png",
                    Units.toEMU(80),
                    Units.toEMU(40)
            );
        }

        XWPFFooter footer = doc.createFooter(HeaderFooterType.DEFAULT);
        footer.createParagraph().createRun().setText("Footer text");

        doc.createParagraph().createRun().setText("Body text");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.write(out);
            return out.toByteArray();
        }
    }
}
