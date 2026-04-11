package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 提示词解析结果模型，负责汇总本次解析命中的提示词、被拒绝的提示词和槽位映射关系，
 * 供预览、快照和运行时组装阶段继续使用。
 */
public class PromptResolveResult {
    /**
     * 最终命中的提示词列表。
     */
    List<ResolvedPromptItem> matchedItems;
    /**
     * 被拒绝的提示词列表。
     */
    List<RejectedPromptItem> rejectedItems;
    /**
     * 运行时槽位到命中提示词列表的映射。
     */
    Map<String, List<ResolvedPromptItem>> slotMapping;
    /**
     * 本次解析采用的策略标识。
     */
    String policyId;
}
