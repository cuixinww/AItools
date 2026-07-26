package com.dg.tools;

import com.dg.tools.extractor.RecursiveExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * dg-tools Spring Boot Application 入口。
 *
 * 提供三种 CLI 模式：
 *   1. 单文件：dg-tools &lt;file&gt;
 *   2. 多文件：dg-tools &lt;file1&gt; &lt;file2&gt; ...
 *   3. 目录扫描：dg-tools --dir &lt;directory&gt;
 */
@SpringBootApplication
@Slf4j
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner runner(RecursiveExtractor extractor) {
        return args -> {
            if (args.length < 1) {
                printUsage();
                return;
            }

            try {
                if ("--dir".equals(args[0])) {
                    if (args.length < 2) {
                        log.error("--dir requires a directory path");
                        return;
                    }
                    processDir(extractor, Paths.get(args[1]));
                    return;
                }

                // 模式 1/2：每个参数作为一个文件
                List<RecursiveExtractor.FileEntry> files = new ArrayList<>();
                for (String arg : args) {
                    Path path = Paths.get(arg);
                    if (!Files.exists(path)) {
                        log.error("File not found: {}", path.toAbsolutePath());
                        continue;
                    }
                    byte[] data = Files.readAllBytes(path);
                    files.add(new RecursiveExtractor.FileEntry(data, path.getFileName().toString()));
                }

                if (files.isEmpty()) {
                    log.error("No valid files to process");
                    return;
                }

                log.info("Starting extraction of {} file(s)...", files.size());
                Path outputDir = extractor.processRootFiles(files);
                log.info("Extraction complete — output: {}", outputDir.toAbsolutePath());

            } catch (Exception e) {
                log.error("Extraction failed", e);
            }
        };
    }

    private void processDir(RecursiveExtractor extractor, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            log.error("Not a directory: {}", dir.toAbsolutePath());
            return;
        }

        List<RecursiveExtractor.FileEntry> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".docx") || name.endsWith(".doc")
                                || name.endsWith(".xlsx") || name.endsWith(".xls")
                                || name.endsWith(".pdf") || name.endsWith(".zip");
                    })
                    .forEach(p -> {
                        try {
                            byte[] data = Files.readAllBytes(p);
                            files.add(new RecursiveExtractor.FileEntry(data,
                                    p.getFileName().toString()));
                        } catch (IOException e) {
                            log.warn("Failed to read file: {}", p, e);
                        }
                    });
        }

        if (files.isEmpty()) {
            log.error("No supported files found in: {}", dir.toAbsolutePath());
            return;
        }

        log.info("Starting extraction of {} file(s) from directory...", files.size());
        Path outputDir = extractor.processRootFiles(files);
        log.info("Extraction complete — output: {}", outputDir.toAbsolutePath());
    }

    private void printUsage() {
        log.info("dg-tools — AI-powered document extraction pipeline");
        log.info("Usage:");
        log.info("  Single file:  dg-tools <file>");
        log.info("  Multiple files: dg-tools <file1> <file2> ...");
        log.info("  Directory scan: dg-tools --dir <directory>");
        log.info("Supported formats: .docx .doc .xlsx .xls .pdf .zip");
    }
}
