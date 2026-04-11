package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.EvidenceBlock;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 主模型执行请求模型，负责汇总主模型调用阶段所需的上下文、证据、候选能力和运行控制信息，
 * 作为主模型编排入口的完整输入载体。
 */
public class MainModelExecutionRequest {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 本轮用户原始输入。
     */
    String userInput;
    /**
     * 结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 输入重构结果。
     */
    InputReconstructionResult reconstructionResult;
    /**
     * 上下文重排结果。
     */
    ContextRerankResult rerankResult;
    /**
     * 工具语义分析结果。
     */
    ToolSemanticResult toolSemanticResult;
    /**
     * 入选的知识证据块。
     */
    List<EvidenceBlock> knowledgeEvidenceBlocks;
    /**
     * 工作记忆片段列表。
     */
    List<String> workingMemorySnippets;
    /**
     * 运行态记忆片段列表。
     */
    List<String> runtimeMemorySnippets;
    /**
     * 检索得到的记忆片段列表。
     */
    List<String> retrievedMemorySnippets;
    /**
     * 知识片段列表。
     */
    List<String> knowledgeSnippets;
    /**
     * 用户偏好片段列表。
     */
    List<String> preferenceSnippets;
    /**
     * 长期记忆片段列表。
     */
    List<String> longTermMemorySnippets;
    /**
     * 当前可执行的候选资源集合。
     */
    List<Resource> executionCandidates;
    /**
     * MCP 资源提示列表。
     */
    List<String> mcpResourceHints;
    /**
     * 工具上下文文本。
     */
    String toolContext;
    /**
     * 当前节点模板策略。
     */
    ContextNodeTemplatePolicy nodeTemplatePolicy;
    /**
     * 回合摘要输入结果。
     */
    SummaryResult roundSummaryInput;
    /**
     * 当前计划标识。
     */
    Long planId;
    /**
     * 当前节点标识。
     */
    Long nodeId;
    /**
     * 当前执行阶段名称。
     */
    String stage;
    /**
     * 修复链路种子信息。
     */
    String repairSeed;
    /**
     * 原始工具结果通道数据。
     */
    Map<String, Object> rawToolResultChannel;
}
