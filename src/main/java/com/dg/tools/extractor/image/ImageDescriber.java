package com.dg.tools.extractor.image;

/**
 * 图片描述器（Phase 1.5 IMAGE 阶段）。
 *
 * 对 Phase 1 UNPACK 拆出的每张图片调用视觉大模型做内容描述，
 * 输出包含标题、类别和详细描述的结构化 JSON，写入 media/{img}.json。
 * 默认实现 {@link NoOpImageDescriber} 永远返回 null（不做描述）。
 *
 * <p>接入视觉模型时，实现此接口即可：接收图片字节数据和格式信息，
 * 调用多模态大模型生成结构化描述。</p>
 *
 * @see NoOpImageDescriber
 * @see ImageDescription
 * @see RecursiveExtractor#processFile
 */
@FunctionalInterface
public interface ImageDescriber {

    /**
     * 对指定图片做视觉理解并返回结构化描述。
     *
     * @param imageData 图片原始字节
     * @param format    图片格式（"png"/"jpg"/"gif" 等）
     * @return 图片描述对象；失败或尚未接入模型时返回 null
     */
    ImageDescription describe(byte[] imageData, String format);
}
