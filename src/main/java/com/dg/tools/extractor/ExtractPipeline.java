package com.dg.tools.extractor;

import com.dg.tools.extractor.handler.AbstractHandler;
import com.dg.tools.extractor.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 提取管道 - 遵循 AGENTS.md 规范的三个阶段提取入口。
 *
 * 与 RecursiveExtractor 架构一致，但使用 Handler 实现：
 *   - Phase 1 (UNPACK): 递归拆包，只提取内嵌文件和图片
 *   - Phase 1.5 (TRIAGE+IMAGE): 预留扩展
 *   - Phase 2 (PARSE): 完整内容解析生成 body.md / chunks/ / data/ / media/
 *
 * 对应 SPEC §2 三阶段提取架构。
 */
@Service
@Slf4j
public class ExtractPipeline {

    private final List<AbstractHandler> handlers;
    private final int maxDepth;
    private final long maxFileSize;

    public ExtractPipeline() {
        this.handlers = List.of();
        this.maxDepth = 10;
        this.maxFileSize = 200L * 1024 * 1024;
    }

    @Autowired
    public ExtractPipeline(
            List<AbstractHandler> handlers,
            @Value("${extractor.pipeline.max-depth:10}") int maxDepth,
            @Value("${extractor.pipeline.max-file-size:209715200}") long maxFileSize) {
        this.handlers = handlers;
        this.maxDepth = maxDepth;
        this.maxFileSize = maxFileSize;
    }

    /**
     * 阶段 1：递归拆包 —— 提取内嵌文件和图片。
     */
    public ExtractionResult unpack(InputStream is, String fileName) {
        log.info("Phase 1 UNPACK: {}", fileName);
        ExtractionResult result = ExtractionResult.of("unknown", fileName);
        try {
            AbstractHandler handler = resolveHandler(fileName);
            if (handler == null) {
                log.warn("No handler for file: {}", fileName);
                result.addError("no handler for: " + fileName);
                return result;
            }
            result = handler.unpack(is, fileName);
        } catch (Exception e) {
            log.error("Unpack failed: {}", fileName, e);
            result.addError("unpack error: " + e.getMessage());
        }
        return result;
    }

    /**
     * 阶段 2：完整内容解析。
     */
    public ExtractionResult extract(InputStream is, String fileName) {
        log.info("Phase 2 PARSE: {}", fileName);
        ExtractionResult result = ExtractionResult.of("unknown", fileName);
        try {
            AbstractHandler handler = resolveHandler(fileName);
            if (handler == null) {
                log.warn("No handler for file: {}", fileName);
                result.addError("no handler for: " + fileName);
                return result;
            }
            result = handler.extract(is, fileName);
        } catch (Exception e) {
            log.error("Extract failed: {}", fileName, e);
            result.addError("extract error: " + e.getMessage());
        }
        return result;
    }

    private AbstractHandler resolveHandler(String fileName) {
        if (fileName == null || handlers == null) return null;
        for (AbstractHandler h : handlers) {
            if (h.supports(fileName)) return h;
        }
        return null;
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    public long getMaxFileSize() {
        return maxFileSize;
    }
}
