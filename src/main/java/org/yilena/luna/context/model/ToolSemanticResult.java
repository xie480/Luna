package org.yilena.luna.context.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载工具执行后的语义化摘要结果，为后续状态推进提供结构化信号。
 */
@Value
@Builder
public class ToolSemanticResult {
    /**
     * 执行工具的名称。
     */
    String toolName;
    /**
     * 工具职责说明。
     */
    String toolDescription;
    /**
     * 原始工具结果的摘要文本。
     */
    String rawResultDigest;
    /**
     * 工具执行状态。
     */
    String toolStatus;
    /**
     * 从工具结果中提炼出的关键事实。
     */
    List<String> keyFacts;
    /**
     * 工具结果对业务流程的影响说明。
     */
    String businessImpact;
    /**
     * 当前仍未解决的问题列表。
     */
    List<String> unresolvedIssues;
    /**
     * 建议的下一步动作。
     */
    String nextStepHint;
    /**
     * 语义摘要结论的置信度。
     */
    double confidence;
    /**
     * 保留原始结构化细节的语义载荷。
     */
    Map<String, Object> semanticPayload;
}
