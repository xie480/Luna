package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载上下文组装结果，汇总最终提示词、章节内容和组装过程元信息。
 */
@Value
@Builder
public class AssembledContext {
    /**
     * 最终生成并提供给模型的完整提示词文本。
     */
    String prompt;
    /**
     * 按章节划分的最终上下文内容。
     */
    Map<String, List<String>> sections;
    /**
     * 规范化后的标准章节内容。
     */
    Map<String, List<String>> canonicalSections;
    /**
     * 组装过程中各渠道候选内容的原始池。
     */
    Map<String, List<String>> candidatePool;
    /**
     * 每个章节的 token 估算值。
     */
    Map<String, Integer> sectionTokenCounts;
    /**
     * 每个章节在总上下文中的 token 占比。
     */
    Map<String, Double> sectionTokenRatios;
    /**
     * 提示词组装阶段输出的辅助元数据。
     */
    Map<String, Object> promptAssemblyMeta;
    /**
     * 对应的上下文快照标识。
     */
    String snapshotId;
}
