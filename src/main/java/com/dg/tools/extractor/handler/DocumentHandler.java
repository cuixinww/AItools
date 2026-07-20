package com.dg.tools.extractor.handler;

import com.dg.tools.extractor.model.ExtractionResult;
import com.dg.tools.extractor.model.UnpackResult;

import java.io.InputStream;

/**
 * 文档处理器统一接口（DocumentHandler）。
 *
 * 每种可被解析的文档类型（docx / doc / xlsx / pdf / zip / 图片）都实现一个该接口，
 * 由 {@link com.dg.tools.extractor.RecursiveExtractor} 按扩展名路由调用。
 *
 * 接口区分两个职责：
 *   - extract(...)  ：阶段 2 的完整内容解析（段落 / 表格 / 图片 / 内嵌文件）；
 *   - unpack(...)   ：阶段 1 的纯二进制拆包（只抽内嵌文件 + 图片，不解析文本）。
 *                    默认实现复用 extract(...) 并丢弃文本元素，多数处理器会按需重写。
 */
public interface DocumentHandler {
    /**
     * 是否支持该文件名（按扩展名判断，如 ".docx"）。
     *
     * @param fileName 文件名或带点的扩展名
     * @return 支持则返回 true
     */
    boolean supports(String fileName);

    /**
     * 阶段 2：完整内容提取 —— 解析段落、表格、图片、内嵌文件。
     * 由 ParsePhase 调用，用于生成 body.md + chunks/ + data/。
     *
     * @param is       文档输入流
     * @param fileName 文件名（用于类型标识）
     * @return 解析结果
     */
    ExtractionResult extract(InputStream is, String fileName);

    /**
     * 阶段 1：纯二进制拆包 —— 把内嵌文件与图片作为原始字节抽取出来。
     * 不做段落 / 表格文本解析。UnpackPhase 调用它把文件保存到磁盘。
     *
     * 默认实现：退化地调用 extract(...) 然后只保留内嵌文件与图片，丢弃文本元素。
     *
     * @param is       文档输入流
     * @param fileName 文件名
     * @return 拆包结果（仅含内嵌文件与图片）
     */
    default UnpackResult unpack(InputStream is, String fileName) {
        ExtractionResult full = extract(is, fileName);
        UnpackResult unpacked = new UnpackResult(full.getFileType(), full.getFileName());
        for (var emb : full.getEmbeddedFiles()) {
            unpacked.addEmbedded(emb);
        }
        for (var img : full.getImages()) {
            unpacked.addImage(img);
        }
        return unpacked;
    }
}
