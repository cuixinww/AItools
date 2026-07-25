package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.DocxHandler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.*;

class RealFileExtractionTest {

    @Test
    void extractRealDocx() throws Exception {
        Path realFile = Paths.get("E:\\silf\\dg_tools\\SCM-NextGen_\u8f6f\u4ef6\u9700\u6c42\u89c4\u683c\u8bf4\u660e\u4e66_V2.3.1.docx");
        assertThat(realFile).exists();

        DocxHandler handler = new DocxHandler();
        var result = handler.extract(Files.newInputStream(realFile), realFile.getFileName().toString());

        assertThat(result).isNotNull();
        assertThat(result.getFileType()).isEqualTo("docx");
        assertThat(result.getElements()).isNotEmpty();
    }
}
