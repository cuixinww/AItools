package com.dg.tools.extractor.triage;

/**
 * LLM 快判结果模型。
 *
 * Phase 1.5 TRIAGE 阶段由 {@link TriageProcessor} 对各嵌入文档做快速相关性判定的输出。
 */
public class RelevanceAssessment {
    private final boolean relevant;
    private final double confidence;
    private final String reason;

    public RelevanceAssessment(boolean relevant, double confidence, String reason) {
        this.relevant = relevant;
        this.confidence = confidence;
        this.reason = reason;
    }

    public boolean isRelevant() { return relevant; }
    public double getConfidence() { return confidence; }
    public String getReason() { return reason; }
}
