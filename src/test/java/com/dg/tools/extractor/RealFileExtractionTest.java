package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;

class RealFileExtractionTest {

    @Test
    void extractRealDocx() throws Exception {
        Path realFile = Paths.get("E:\\silf\\dg_tools\\SCM-NextGen_软件需求规格说明书_V2.3.1.docx");
        assertThat(realFile).exists();

        RecursiveExtractor extractor = new RecursiveExtractor();
        extractor.setHandlers(
                new DocxHandler(), new DocHandler(),
                new ExcelHandler(), new PdfHandler(),
                new ZipHandler(), new ImageHandler()
        );

        Path outputDir = Files.createTempDirectory("extract_real_");
        String fileName = realFile.getFileName().toString();
        extractor.extract(
                Files.newInputStream(realFile),
                fileName,
                "test_real",
                outputDir
        );

        System.out.println("=== 提取目录结构 ===");
        try (Stream<Path> walk = Files.walk(outputDir)) {
            walk.forEach(p -> {
                if (!p.equals(outputDir)) {
                    System.out.println("  " + outputDir.relativize(p));
                }
            });
        }

        // Find root dir by sanitized name
        String rootBase = RecursiveExtractor.fileNameToBaseName(fileName);
        Path bodyMd = outputDir.resolve(rootBase + "/" + rootBase + ".md");
        assertThat(bodyMd).exists();
        String content = Files.readString(bodyMd);
        long paraCount = content.lines().filter(l -> l.startsWith("# POS:") && l.contains("TYPE: paragraph")).count();
        long tableCount = content.lines().filter(l -> l.startsWith("# POS:") && l.contains("TYPE: table")).count();
        long embedCount = content.lines().filter(l -> l.startsWith("# POS:") && l.contains("TYPE: embed")).count();
        long imgCount = content.lines().filter(l -> l.startsWith("# POS:") && l.contains("TYPE: image")).count();

        System.out.println("\n=== " + rootBase + ".md 摘要 ===");
        System.out.println("  段落(paragraph): " + paraCount);
        System.out.println("  表格(table): " + tableCount);
        System.out.println("  嵌入文件(embed): " + embedCount);
        System.out.println("  图片(image): " + imgCount);
        System.out.println("  总行数: " + content.lines().count());

        System.out.println("\n=== " + rootBase + ".md 前40行 ===");
        content.lines().limit(40).forEach(System.out::println);

        // Check manifest
        Path manifest = outputDir.resolve("manifest.json");
        assertThat(manifest).exists();
        System.out.println("\n=== manifest.json ===");
        System.out.println(Files.readString(manifest));

        // Check media
        Path mediaDir = outputDir.resolve(rootBase + "/media");
        if (Files.exists(mediaDir)) {
            System.out.println("\n=== " + rootBase + "/media ===");
            try (Stream<Path> files = Files.list(mediaDir)) {
                files.forEach(f -> System.out.println("  " + f.getFileName()));
            }
        }

        // Check data
        Path dataDir = outputDir.resolve(rootBase + "/data");
        if (Files.exists(dataDir)) {
            System.out.println("\n=== " + rootBase + "/data ===");
            try (Stream<Path> files = Files.list(dataDir)) {
                files.forEach(f -> {
                    try { System.out.println("  " + f.getFileName() + " (" + Files.size(f) + " bytes)");
                    } catch (Exception ignored) {}
                });
            }
        }

        // Cleanup
        try (Stream<Path> walk = Files.walk(outputDir)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
        }
    }
}
