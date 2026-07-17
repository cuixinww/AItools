package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import java.nio.file.*;

public class RealFileExtractionRunner {
    public static void main(String[] args) throws Exception {
        Path realFile = Paths.get("E:\\silf\\dg_tools\\SCM-NextGen_软件需求规格说明书_V2.3.1.docx");
        Path outputDir = Paths.get("E:\\silf\\dg_tools\\extracted_output");

        RecursiveExtractor extractor = new RecursiveExtractor();
        extractor.setHandlers(
                new DocxHandler(), new DocHandler(),
                new ExcelHandler(), new PdfHandler(),
                new ZipHandler(), new ImageHandler()
        );

        extractor.extract(
                Files.newInputStream(realFile),
                realFile.getFileName().toString(),
                "extract_" + java.time.LocalDateTime.now().format(
                        java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")),
                outputDir
        );

        System.out.println("提取完成！输出目录: " + outputDir.toAbsolutePath());
        System.out.println("文件列表:");
        try (var walk = Files.walk(outputDir)) {
            walk.filter(p -> !p.equals(outputDir))
                .forEach(p -> System.out.println("  " + outputDir.relativize(p)));
        }
    }
}
