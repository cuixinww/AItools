package com.dg.tools.extractor.triage;

/**
 * 文档相关性判定器（Phase 1.5 TRIAGE 阶段）。
 *
 * 在 Phase 1 UNPACK 之后、递归拆包之前，对每个内嵌文档做快速相关性判定：
 * 决定该文档是否值得进入 Phase 2 完整解析。
 * 默认实现 {@link NoOpTriageProcessor} 永远返回 RELEVANT（不跳过任何文档）。
 *
 * <p>接入 LLM 时，实现此接口即可：根据文件内容 + 文件名调用语言模型，
 * 返回 RelevanceAssessment 表示是否相关及判定置信度。</p>
 *
 * @see NoOpTriageProcessor
 * @see RelevanceAssessment
 * @see RecursiveExtractor#processFile
 */
@FunctionalInterface
public interface TriageProcessor {

    /**
     * 判定指定文档是否与当前用户需求相关。
     *
     * @param fileData 文档的原始字节数据
     * @param fileName 文档文件名
     * @return 相关性判定结果
     */
    RelevanceAssessment assess(byte[] fileData, String fileName);
}
