package com.dg.tools.extractor.triage;

import com.dg.tools.extractor.model.ExtractionResult;

/**
 * 文档相关性判定器（Phase 1.5 TRIAGE）。
 *
 * 对 Phase 1 拆出的每个嵌入文档做快速 LLM 快判，决定是否在 Phase 2 中继续解析。
 * 默认实现 {@link NoOpTriageProcessor} 永远返回 RELEVANT（不跳过任何文档）。
 *
 * 接入 LLM 时，只需实现此接口即可：
 * <pre>
 *   TriageProcessor triage = (fileData, fileName) -> {
 *       // 调用 LLM，返回 RelevanceAssessment
 *   };
 * </pre>
 */
@FunctionalInterface
public interface TriageProcessor {
    RelevanceAssessment assess(byte[] fileData, String fileName);
}
