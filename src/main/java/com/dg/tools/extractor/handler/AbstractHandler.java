package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * 抽象处理器基类 — 为所有具体处理器提供模板方法与通用守卫。
 *
 * 模板方法：
 *   - {@link #unpack(InputStream, String)} 集中处理 null 校验、顶层异常捕获与日志，
 *     子类只需实现 {@link #doUnpack(InputStream, String)}。
 *   - {@link #extract(InputStream, String)} 同理，子类实现 {@link #doExtract(InputStream, String)}。
 *
 * 默认 {@link #doUnpack} 实现调用 {@link #doExtract} 并转换结果，适合无法独立实现
 * 高效 unpack 的处理器（如 DocHandler）。其余处理器应各自重写 doUnpack 以
 * 实现仅抽取嵌入文件/图片的轻量逻辑，满足 SPEC 阶段 1 内存隔离要求。
 *
 * 对应 SPEC §9 路由表 + AGENTS.md §3.4 并发安全：所有子类应为无状态 bean。
 */
@Slf4j
public abstract class AbstractHandler {

    protected AbstractHandler() {}

    protected static void requireInput(InputStream is) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
    }

    public abstract boolean supports(String fileName);

    public ExtractionResult unpack(InputStream is, String fileName) {
        requireInput(is);
        try {
            return doUnpack(is, fileName);
        } catch (Exception e) {
            log.error("Failed to unpack: {}", fileName, e);
            ExtractionResult result = ExtractionResult.of("unknown", fileName);
            result.addError("unpack error: " + e.getMessage());
            return result;
        }
    }

    public ExtractionResult extract(InputStream is, String fileName) {
        requireInput(is);
        try {
            return doExtract(is, fileName);
        } catch (Exception e) {
            log.error("Failed to parse: {}", fileName, e);
            ExtractionResult result = ExtractionResult.of("unknown", fileName);
            result.addError("parse error: " + e.getMessage());
            return result;
        }
    }

    protected ExtractionResult doUnpack(InputStream is, String fileName) throws Exception {
        ExtractionResult full = doExtract(is, fileName);
        ExtractionResult unpacked = ExtractionResult.of(full.getFileType(), full.getFileName());
        for (var emb : full.getEmbeddedFiles()) {
            unpacked.addEmbedded(emb);
        }
        for (var img : full.getImages()) {
            unpacked.addImage(img);
        }
        return unpacked;
    }

    protected abstract ExtractionResult doExtract(InputStream is, String fileName) throws Exception;
}
