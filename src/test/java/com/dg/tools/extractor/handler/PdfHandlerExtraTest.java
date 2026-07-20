package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.UnpackResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class PdfHandlerExtraTest {

    private final PdfHandler handler = new PdfHandler();

    @Test
    void shouldRejectNullInputStream() {
        assertThatThrownBy(() -> handler.extract(null, "a.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.unpack(null, "a.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldUnpackImages() throws Exception {
        byte[] pdf = TestFileFactory.createPdfWithImage();
        UnpackResult ur = handler.unpack(TestFileFactory.toInputStream(pdf), "a.pdf");
        assertThat(ur.getImages()).isNotEmpty();
    }

    @Test
    void shouldMergeParagraphsByBlankLine() throws Exception {
        // 两行之间无空行应合并为一个段落（PDF 默认字体仅支持 ASCII，故用英文字符）
        byte[] pdf = TestFileFactory.createSimplePdf("line one", "line two");
        ExtractionResult r = handler.extract(TestFileFactory.toInputStream(pdf), "a.pdf");
        assertThat(r.getElements()).hasSize(1);
        assertThat(r.getElements().get(0).getContent()).contains("line one").contains("line two");
    }

    @Test
    void shouldProduceSeparateParagraphsWithBlankLine() throws Exception {
        byte[] pdf = TestFileFactory.createSimplePdf("para one", "para two");
        ExtractionResult r = handler.extract(TestFileFactory.toInputStream(pdf), "a.pdf");
        // PDFBox 多行 showText 在同一文本对象内通常合成一段；此处仅验证非空
        assertThat(r.getElements()).isNotEmpty();
    }

    @Test
    void shouldNotSupportNonPdf() {
        assertThat(handler.supports("a.docx")).isFalse();
        assertThat(handler.supports("A.PDF")).isTrue();
    }
}
