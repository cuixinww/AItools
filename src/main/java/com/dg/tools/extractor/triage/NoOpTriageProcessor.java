package com.dg.tools.extractor.triage;

/**
 * 空操作的文档相关性判定器。
 *
 * 默认行为：永远返回 RELEVANT（relevant=true, confidence=1.0），不跳过任何文档。
 * Phase 1.5 TRIAGE 尚未接入 LLM 时使用此实现，保证管道正常运行。
 *
 * @see TriageProcessor
 */
public class NoOpTriageProcessor implements TriageProcessor {
    /**
     * 统一返回 RELEVANT，不进行任何内容分析。
     */
    @Override
    public RelevanceAssessment assess(byte[] fileData, String fileName) {
        return new RelevanceAssessment(true, 1.0, "no triage processor configured — default to relevant");
    }
}
