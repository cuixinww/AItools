package com.dg.tools.extractor.image;

/**
 * 图片描述结果模型（IMAGE 阶段输出）。
 *
 * 由 {@link ImageDescriber} 对图片做视觉理解后生成，包含：
 * <ul>
 *   <li>title — 图片简短标题</li>
 *   <li>category — 图片类别（SPEC §5.2）：UI_MOCKUP / FLOWCHART / TABLE_SCREENSHOT / FORMULA / SIGNATURE_STAMP / OTHER</li>
 *   <li>description — 详细描述，概括图片核心内容和上下文</li>
 * </ul>
 * 结构化 JSON 格式写入 media/{img}.json。
 */
public class ImageDescription {

    /** 图片简短标题。 */
    private final String title;

    /** 图片类别，枚举值见 SPEC §5.2。 */
    private final String category;

    /** 详细描述文本。 */
    private final String description;

    public ImageDescription(String title, String category, String description) {
        this.title = title;
        this.category = category;
        this.description = description;
    }

    /** 获取图片简短标题。 */
    public String getTitle() { return title; }

    /** 获取图片类别标识。 */
    public String getCategory() { return category; }

    /** 获取详细描述文本。 */
    public String getDescription() { return description; }
}
