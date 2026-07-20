package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.TestFileFactory;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.UnpackResult;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.*;

class DocumentHandlerDefaultMethodTest {

    /** 一个只实现 extract 的匿名处理器，用于验证默认 unpack 会委托给 extract 并丢弃文本。 */
    private static final DocumentHandler SIMPLE = new DocumentHandler() {
        @Override
        public boolean supports(String fileName) {
            return fileName != null && fileName.endsWith(".sim");
        }

        @Override
        public ExtractionResult extract(InputStream is, String fileName) {
            ExtractionResult r = new ExtractionResult("sim", fileName);
            r.addElement(new com.dg.tools.extractor.model.Element(0, "paragraph", "text"));
            r.addImage(new com.dg.tools.extractor.model.ImageFile("i.png", 1, new byte[]{1}, "png"));
            r.addEmbedded(new com.dg.tools.extractor.model.EmbeddedFile("e.bin", 2, new byte[]{2}));
            return r;
        }
    };

    @Test
    void defaultUnpackShouldDelegateToExtractAndKeepBinaries() throws Exception {
        byte[] data = "sim content".getBytes();
        UnpackResult ur = SIMPLE.unpack(TestFileFactory.toInputStream(data), "x.sim");
        // 默认实现应保留内嵌文件与图片，但不含文本元素
        assertThat(ur.getEmbeddedFiles()).hasSize(1);
        assertThat(ur.getImages()).hasSize(1);
    }

    @Test
    void supportsShouldRouteByExtension() {
        assertThat(SIMPLE.supports("a.sim")).isTrue();
        assertThat(SIMPLE.supports("a.txt")).isFalse();
        assertThat(SIMPLE.supports(null)).isFalse();
    }
}
