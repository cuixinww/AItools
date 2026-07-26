package com.dg.tools.extractor.image;

/**
 * 空操作的图片描述器。
 *
 * 默认行为：永远返回 null，不做视觉描述。
 * Phase 1.5 IMAGE 尚未接入视觉模型时使用此实现，保证管道正常运行。
 *
 * @see ImageDescriber
 */
public class NoOpImageDescriber implements ImageDescriber {
    /** 统一返回 null，不进行任何视觉分析。 */
    @Override
    public ImageDescription describe(byte[] imageData, String format) {
        return null;
    }
}
