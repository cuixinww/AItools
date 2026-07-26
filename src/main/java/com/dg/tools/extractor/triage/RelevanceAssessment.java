package com.dg.tools.extractor.triage;

/**
 * LLM 快判结果模型（TRIAGE 阶段输出）。
 *
 * 由 {@link TriageProcessor} 对每个嵌入文档做相关性判定后生成，
 * 包含是否相关、判定置信度和原因说明三个字段。
 */
public class RelevanceAssessment {

    /** 文档是否与当前提取需求相关。true 表示进入 Phase 2 解析，false 表示跳过。 */
    private final boolean relevant;

    /** 判定置信度，范围 0.0 ~ 1.0，值越高表示模型越有把握。 */
    private final double confidence;

    /** 判定原因说明，用于日志记录和调试追踪。 */
    private final String reason;

    public RelevanceAssessment(boolean relevant, double confidence, String reason) {
        this.relevant = relevant;
        this.confidence = confidence;
        this.reason = reason;
    }

    /** 返回文档相关性标记。 */
    public boolean isRelevant() { return relevant; }

    /** 返回判定置信度。 */
    public double getConfidence() { return confidence; }

    /** 返回判定原因说明。 */
    public String getReason() { return reason; }
}
