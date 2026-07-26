package com.dg.tools.extractor.image;

/**
 * 图片描述器（Phase 1.5 IMAGE）。
 *
 * 对 Phase 1 拆出的每张图片调用视觉大模型做内容描述。
 * 默认实现 {@link NoOpImageDescriber} 永远返回 null。
 *
 * 接入视觉模型时，只需实现此接口即可：
 * <pre>
 *   ImageDescriber describer = (imageData, format) -> {
 *       // 调用视觉模型，返回 ImageDescription
 *   };
 * </pre>
 */
@FunctionalInterface
public interface ImageDescriber {
    ImageDescription describe(byte[] imageData, String format);
}
