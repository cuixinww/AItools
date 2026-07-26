package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * 抽象处理器基类（模板方法模式）。
 *
 * 为所有具体处理器提供统一的输入校验、异常捕获和错误记录机制。
 * <ul>
 *   <li>{@link #unpack}：Phase 1 拆包，提取内嵌文件和图片</li>
 *   <li>{@link #extract}：Phase 2 解析，提取全文内容并生成 Element 列表</li>
 * </ul>
 *
 * 子类只需实现 {@link #doUnpack} 和 {@link #doExtract} 即可。
 * 默认 {@link #doUnpack} 实现调用 {@link #doExtract} 并提取 embeddedFiles 和 images，
 * 适合无法独立实现高效 unpack 的处理器（如 DocHandler）。
 * DocxHandler、ExcelHandler、ZipHandler 等应各自重写 doUnpack 以实现轻量级逻辑。
 *
 * <p>线程安全保证：此类及其子类均为无状态 bean，
 * 每次 extract/unpack 调用创建独立的 ExtractionResult 实例，不存在共享可变状态。</p>
 *
 * @see DocxHandler
 * @see DocHandler
 * @see ExcelHandler
 * @see PdfHandler
 * @see ZipHandler
 */
@Slf4j
public abstract class AbstractHandler {

    protected AbstractHandler() {}

    /**
     * 校验输入流非 null。
     * @throws IllegalArgumentException 如果 is 为 null
     */
    protected static void requireInput(InputStream is) {
        if (is == null) {
            throw new IllegalArgumentException("InputStream must not be null");
        }
    }

    /**
     * 判断当前 Handler 是否支持指定文件名的文档类型。
     * 由子类根据支持的扩展名实现。
     *
     * @param fileName 文件名
     * @return true 如果此 Handler 可以处理该文件
     */
    public abstract boolean supports(String fileName);

    /**
     * Phase 1：拆包 — 提取文档中的内嵌文件和图片（不解析文本内容）。
     * 统一进行 null 校验和异常捕获，失败时返回带错误信息的 ExtractionResult 而非抛异常。
     *
     * @param is       文档输入流
     * @param fileName 文件名
     * @return 包含内嵌文件和图片的 ExtractionResult
     */
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

    /**
     * Phase 2：解析 — 提取文档完整内容（段落、表格、图片引用等）并生成 Element 列表。
     * 统一进行 null 校验和异常捕获，失败时返回带错误信息的 ExtractionResult 而非抛异常。
     *
     * @param is       文档输入流
     * @param fileName 文件名
     * @return 包含完整内容元素的 ExtractionResult
     */
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

    /**
     * 默认的 unpack 实现：调用 doExtract 获取完整结果，然后仅提取 embeddedFiles 和 images 返回。
     * 这种实现的缺点是 unpack 阶段会完整解析文档，不适合大文档。
     * 推荐的子类应自行实现轻量级的 doUnpack。
     *
     * @param is       文档输入流
     * @param fileName 文件名
     * @return 仅包含内嵌文件和图片的 ExtractionResult
     * @throws Exception 当 doExtract 失败时抛出
     */
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

    /**
     * 子类必须实现：执行文档的完整内容解析。
     * 子类在此方法中读取 InputStream，提取 Element、embeddedFiles、images 等，
     * 返回填充好的 ExtractionResult。
     *
     * @param is       文档输入流
     * @param fileName 文件名
     * @return 包含完整解析内容的 ExtractionResult
     * @throws Exception 解析过程中任何异常都会由上层 extract() 方法捕获并记录到 errors 中
     */
    protected abstract ExtractionResult doExtract(InputStream is, String fileName) throws Exception;
}
