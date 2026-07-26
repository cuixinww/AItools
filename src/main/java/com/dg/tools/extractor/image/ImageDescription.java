package com.dg.tools.extractor.image;

/**
 * 图片描述结果模型。
 *
 * Phase 1.5 IMAGE 阶段由 {@link ImageDescriber} 对图片做视觉理解后的输出。
 * category 对应 SPEC §5.2 的枚举：UI_MOCKUP / FLOWCHART / TABLE_SCREENSHOT / FORMULA / SIGNATURE_STAMP / OTHER。
 */
public class ImageDescription {
    private final String title;
    private final String category;
    private final String description;

    public ImageDescription(String title, String category, String description) {
        this.title = title;
        this.category = category;
        this.description = description;
    }

    public String getTitle() { return title; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
}
