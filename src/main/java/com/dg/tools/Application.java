package com.dg.tools;

import com.dg.tools.extractor.ExtractPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * dg-tools Spring Boot Application 入口。
 *
 * 提供两种运行模式：
 *   1. CLI 模式 — 通过 CommandLineRunner 处理文件（待实现）
 *   2. 库模式 — 直接注入 ExtractPipeline 调用 extract()
 */
@SpringBootApplication
@Slf4j
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @Bean
    CommandLineRunner runner(ExtractPipeline pipeline) {
        return args -> {
            if (args.length < 1) {
                log.info("Usage: dg-tools <file> [sessionId]");
                return;
            }
            // CLI mode delegated to pipeline (reserved for future use)
        };
    }
}
