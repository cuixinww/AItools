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
 * 提取管道 - Spring 集成入口。
 *
 * 对外提供两个方法：{@link #unpack}（Phase 1）和 {@link #extract}（Phase 2）。
 * 内部通过 {@link AbstractHandler} 路由表找到对应 Handler 并委托其执行。
 * <p><b>注意</b>：此类是精简版管道，适用于单次独立解析场景（不递归拆包）。
 * 需要完整递归能力的场景应使用 {@link RecursiveExtractor}。</p>
 *
 * @see RecursiveExtractor
 */
@Service
@Slf4j
public class ExtractPipeline {

    /** 注册的 Handler 列表，由 Spring 自动注入所有 @Component Handler。 */
    private final List<AbstractHandler> handlers;

    /** 最大递归深度。默认 10 层。 */
    private final int maxDepth;

    /** 最大文件字节数。默认 209,715,200 (200 MB)。 */
    private final long maxFileSize;

    /**
     * 无参构造函数：空 Handlers + 默认阈值。
     * 被 Spring 忽略（直接走 @Autowired 构造函数），仅作为安全回退。
     */
    public ExtractPipeline() {
        this.handlers = List.of();
        this.maxDepth = 10;
        this.maxFileSize = 200L * 1024 * 1024;
    }

    /**
     * 通过 Spring 配置注入 handlers 和阈值参数。
     * @param handlers 所有 @Component 处理器列表
     * @param maxDepth 最大递归深度
     * @param maxFileSize 最大文件大小（字节）
     */
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
     * Phase 1：拆包 —— 提取内嵌文件和图片（不解析文本内容）。
     * 根据 fileName 路由到对应的 Handler.unpack() 方法。
     */
    public ExtractionResult unpack(InputStream is, String fileName) {
        log.info("Phase 1 UNPACK: {}", fileName);
        // 失败时返回带错误信息的空结果
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
     * Phase 2：完整内容解析 —— 生成 Element 列表。
     * 根据 fileName 路由到对应的 Handler.extract() 方法。
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

    /**
     * 根据文件名从 handlers 列表中查找匹配的 Handler。
     * 遍历顺序由 Spring Bean 注册顺序决定。
     */
    private AbstractHandler resolveHandler(String fileName) {
        if (fileName == null || handlers == null) return null;
        for (AbstractHandler h : handlers) {
            if (h.supports(fileName)) return h;
        }
        return null;
    }

    /** 获取配置的递归深度上限。 */
    public int getMaxDepth() {
        return maxDepth;
    }

    /** 获取配置的文件大小上限（字节）。 */
    public long getMaxFileSize() {
        return maxFileSize;
    }
}
