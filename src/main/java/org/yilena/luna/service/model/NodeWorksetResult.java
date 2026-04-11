package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.entity.Resource;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 节点工作集结果模型，负责汇总当前节点阶段筛出的查询语句、候选能力、证据片段和失效原因，
 * 为工具决策和主模型执行提供节点级工作上下文。
 */
public class NodeWorksetResult {
    /**
     * MCP 驱动输入文本。
     */
    String mcpDrivenInput;
    /**
     * RAG 查询语句。
     */
    String ragQuery;
    /**
     * 记忆检索查询语句。
     */
    String memoryQuery;
    /**
     * MCP 预排序候选列表。
     */
    List<Map<String, Object>> mcpPreRankedCandidates;
    /**
     * 上下文重排结果。
     */
    ContextRerankResult rerankResult;
    /**
     * 各节点对应的重排理由。
     */
    Map<String, String> rerankRationaleByNode;
    /**
     * 最终选中的知识证据块。
     */
    List<EvidenceBlock> selectedKnowledgeEvidenceBlocks;
    /**
     * 最终选中的知识证据引用。
     */
    List<String> selectedKnowledgeEvidenceRefs;
    /**
     * 最终选中的知识片段。
     */
    List<String> selectedKnowledgeSnippets;
    /**
     * 最终选中的记忆片段。
     */
    List<String> selectedMemorySnippets;
    /**
     * 最终选中的偏好片段。
     */
    List<String> selectedPreferenceSnippets;
    /**
     * 最终选中的工具候选名称列表。
     */
    List<String> selectedToolCandidateNames;
    /**
     * 最终选中的 MCP 工具候选名称列表。
     */
    List<String> selectedMcpToolCandidateNames;
    /**
     * 最终选中的提示词候选名称列表。
     */
    List<String> selectedPromptCandidateNames;
    /**
     * 最终选中的资源候选名称列表。
     */
    List<String> selectedResourceCandidateNames;
    /**
     * 最终选中的工作流候选名称列表。
     */
    List<String> selectedWorkflowCandidateNames;
    /**
     * 最终选中的提示资源名称列表。
     */
    List<String> selectedPromptResourceNames;
    /**
     * 被判定失效的证据引用列表。
     */
    List<String> invalidatedEvidenceRefs;
    /**
     * 被判定失效的能力名称列表。
     */
    List<String> invalidatedCapabilityNames;
    /**
     * 各失效引用对应的失效原因。
     */
    Map<String, String> invalidationReasonsByRef;
    /**
     * 最终保留的执行候选资源。
     */
    List<Resource> executionCandidates;
    /**
     * 输出给后续阶段的 MCP 资源提示。
     */
    List<String> mcpResourceHints;
}
