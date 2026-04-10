package org.yilena.luna.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 工具调用上下文实体，负责在对话、Agent 和审批链路之间透传工具决策所需的关键上下文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallingContext {

    /**
     * 原始聊天会话键，用于工具调用后恢复对应会话。
     */
    private String chatSessionKey;
    /**
     * 触发本次工具决策的用户原始输入。
     */
    private String userInput;
    /**
     * 传递给工具决策节点的治理后输入内容。
     */
    private String toolDecisionInput;
    /**
     * 治理输入的签名值，用于识别是否命中相同的决策上下文。
     */
    private String governedInputSignature;
    /**
     * 组装后的工具决策上下文文本，供下游审批或执行复用。
     */
    private String assembledDecisionContext;
    /**
     * 当前轮次命中的记忆片段集合。
     */
    private List<String> memorySnippets;
    /**
     * 当前轮次命中的知识片段集合。
     */
    private List<String> knowledgeSnippets;
    /**
     * 当前轮次命中的用户偏好片段集合。
     */
    private List<String> preferenceSnippets;
    /**
     * 当前轮次命中的长期记忆片段集合。
     */
    private List<String> longTermMemorySnippets;
    /**
     * 本轮候选可执行资源列表，用于工具选择或审批展示。
     */
    private List<Resource> executionCandidates;
    /**
     * MCP 资源提示列表，用于辅助后续工具决策。
     */
    private List<String> mcpResourceHints;
    /**
     * 工具执行轨迹明细，用于审批续聊或审计回放。
     */
    private List<Map<String, Object>> toolExecutionTraces;
}
