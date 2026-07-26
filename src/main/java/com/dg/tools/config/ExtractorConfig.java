package com.dg.tools.config;

import com.dg.tools.extractor.StoreWriter;
import com.dg.tools.extractor.TypeDetector;
import com.dg.tools.extractor.image.ImageDescriber;
import com.dg.tools.extractor.image.NoOpImageDescriber;
import com.dg.tools.extractor.triage.NoOpTriageProcessor;
import com.dg.tools.extractor.triage.TriageProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提取器模块 Spring 配置。
 *
 * 将无状态工具类（StoreWriter / TypeDetector）注册为 Spring Bean，
 * 提供 Phase 1.5 可插拔接口（TriageProcessor / ImageDescriber）的默认实现。
 *
 * 接入 LLM 时，只需在项目中定义自己的 TriageProcessor / ImageDescriber Bean，
 * 默认的 NoOp 实现会被自动覆盖（@ConditionalOnMissingBean）。
 */
@Configuration
public class ExtractorConfig {

    @Bean
    StoreWriter storeWriter() {
        return new StoreWriter();
    }

    @Bean
    TypeDetector typeDetector() {
        return new TypeDetector();
    }

    /**
     * 默认：空操作文档过滤（永远放行）。
     * 接入 LLM 时定义同名 Bean 即可覆盖。
     */
    @Bean
    @ConditionalOnMissingBean(TriageProcessor.class)
    TriageProcessor triageProcessor() {
        return new NoOpTriageProcessor();
    }

    /**
     * 默认：空操作图片描述（永远返回 null）。
     * 接入视觉模型时定义同名 Bean 即可覆盖。
     */
    @Bean
    @ConditionalOnMissingBean(ImageDescriber.class)
    ImageDescriber imageDescriber() {
        return new NoOpImageDescriber();
    }
}
