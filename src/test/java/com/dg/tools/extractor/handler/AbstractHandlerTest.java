package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.Element;
import com.dg.tools.extractor.model.EmbeddedFile;
import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.ImageFile;
import com.dg.tools.extractor.handler.AbstractHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class AbstractHandlerTest {

    @Test
    void unpackDelegatesToDoExtractByDefault() throws Exception {
        AbstractHandler handler = new AbstractHandler() {
            @Override
            public boolean supports(String fileName) { return true; }

            @Override
            protected ExtractionResult doExtract(InputStream is, String fileName) {
                ExtractionResult r = ExtractionResult.of("test", fileName);
                r.addImage(new ImageFile("img.png", 0, new byte[]{1}, "png"));
                r.addEmbedded(new EmbeddedFile("emb.bin", 0, new byte[]{2}));
                return r;
            }
        };
        ExtractionResult unpacked = handler.unpack(
                new ByteArrayInputStream(new byte[0]), "test.bin");
        assertThat(unpacked.getImages()).hasSize(1);
        assertThat(unpacked.getEmbeddedFiles()).hasSize(1);
        assertThat(unpacked.getFileType()).isEqualTo("test");
    }

    @Test
    void unpackFiltersOutElements() throws Exception {
        AbstractHandler handler = new AbstractHandler() {
            @Override
            public boolean supports(String fileName) { return true; }

            @Override
            protected ExtractionResult doExtract(InputStream is, String fileName) {
                ExtractionResult r = ExtractionResult.of("test", fileName);
                r.addElement(new Element(0, "p", "text"));
                r.addImage(new ImageFile("img.png", 1, new byte[]{1}, "png"));
                return r;
            }
        };
        ExtractionResult unpacked = handler.unpack(
                new ByteArrayInputStream(new byte[0]), "test.bin");
        assertThat(unpacked.getElements()).isEmpty();
        assertThat(unpacked.getImages()).hasSize(1);
    }

    @Test
    void unpackCatchesExceptionAndReturnsResultWithError() {
        AbstractHandler handler = new AbstractHandler() {
            @Override
            public boolean supports(String fileName) { return true; }

            @Override
            protected ExtractionResult doUnpack(InputStream is, String fileName) {
                throw new RuntimeException("unpack failed");
            }

            @Override
            protected ExtractionResult doExtract(InputStream is, String fileName) {
                throw new RuntimeException("extract failed");
            }
        };
        ExtractionResult r = handler.unpack(
                new ByteArrayInputStream(new byte[0]), "bad.bin");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("unpack error");
    }

    @Test
    void extractCatchesExceptionAndReturnsResultWithError() {
        AbstractHandler handler = new AbstractHandler() {
            @Override
            public boolean supports(String fileName) { return true; }

            @Override
            protected ExtractionResult doExtract(InputStream is, String fileName) {
                throw new RuntimeException("extract error");
            }
        };
        ExtractionResult r = handler.extract(
                new ByteArrayInputStream(new byte[0]), "bad.bin");
        assertThat(r.getErrors()).isNotEmpty();
        assertThat(r.getErrors().get(0)).contains("parse error");
    }

    @Test
    void requireInputThrowsOnNull() {
        AbstractHandler handler = new AbstractHandler() {
            @Override
            public boolean supports(String fileName) { return true; }

            @Override
            protected ExtractionResult doExtract(InputStream is, String fileName) {
                return ExtractionResult.of("test", fileName);
            }
        };
        assertThatThrownBy(() -> handler.unpack(null, "test.bin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> handler.extract(null, "test.bin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
