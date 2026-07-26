package com.dg.tools;

import com.dg.tools.extractor.RecursiveExtractor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * dg-tools Spring Boot Application 入口。
 *
 * 提供两种运行模式：
 *   1. CLI 模式 — 直接处理文件：mvn spring-boot:run -Dspring-boot.run.arguments="file.docx"
 *   2. 库模式 — 直接注入 RecursiveExtractor 调用 processRoot()
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
                log.info("Usage: dg-tools <file> [sessionId]");
                log.info("Example: dg-tools doc/保险项目用户需求说明书.doc");
                return;
            }
            try {
                Path source = Paths.get(args[0]);
                if (!Files.exists(source)) {
                    log.error("File not found: {}", source.toAbsolutePath());
                    return;
                }
                String sessionId = args.length >= 2 ? args[1]
                        : "extract_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
                log.info("Starting extraction: {} → session {}", source.getFileName(), sessionId);
                byte[] data = Files.readAllBytes(source);
                Path outputDir = extractor.processRoot(data, source.getFileName().toString(), sessionId);
                log.info("Extraction complete — output: {}", outputDir.toAbsolutePath());
            } catch (Exception e) {
                log.error("Extraction failed", e);
            }
        };
    }
}
