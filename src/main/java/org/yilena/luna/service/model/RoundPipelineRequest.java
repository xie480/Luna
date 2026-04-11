package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.context.model.ContextNodeTemplatePolicy;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 单轮流水线请求模型，负责汇总单轮执行所需的编排决策、上下文、候选资源和控制开关，
 * 作为回合流水线的统一输入对象。
 */
public class RoundPipelineRequest {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 本轮用户输入。
     */
    String userInput;
    /**
     * 会话编排决策结果。
     */
    OrchestrationDecision decision;
    /**
     * 结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 输入重构结果。
     */
    InputReconstructionResult reconstructionResult;
    /**
     * 节点工作集结果。
     */
    NodeWorksetResult nodeWorksetResult;
    /**
     * 工具语义分析结果。
     */
    ToolSemanticResult toolSemanticResult;
    /**
     * 工作记忆片段列表。
     */
    List<String> workingMemorySnippets;
    /**
     * 运行态记忆片段列表。
     */
    List<String> runtimeMemorySnippets;
    /**
     * 检索记忆片段列表。
     */
    List<String> retrievedMemorySnippets;
    /**
     * 知识片段列表。
     */
    List<String> knowledgeSnippets;
    /**
     * 偏好片段列表。
     */
    List<String> preferenceSnippets;
    /**
     * 长期记忆片段列表。
     */
    List<String> longTermMemorySnippets;
    /**
     * 当前执行候选资源。
     */
    List<Resource> executionCandidates;
    /**
     * MCP 资源提示列表。
     */
    List<String> mcpResourceHints;
    /**
     * 上下文节点模板策略。
     */
    ContextNodeTemplatePolicy nodeTemplatePolicy;
    /**
     * 工具上下文文本。
     */
    String toolContext;
    /**
     * 当前执行阶段名称。
     */
    String stage;
    /**
     * 修复链路种子信息。
     */
    String repairSeed;
    /**
     * 是否执行主模型阶段。
     */
    boolean runMainModel;
    /**
     * 覆盖使用的回复文本。
     */
    String assistantReplyOverride;
    /**
     * 预组装阶段触发来源。
     */
    String preAssemblyTriggerSource;
    /**
     * 摘要阶段触发来源。
     */
    String postSummaryTriggerSource;
    /**
     * 是否用摘要替换历史记录。
     */
    boolean replaceHistoryWithSummary;
    /**
     * 是否执行轮次状态写回。
     */
    boolean writeRoundState;
    /**
     * 最近一次上下文快照标识。
     */
    String latestSnapshotId;
    /**
     * 最近一次工具原始结果引用。
     */
    String latestToolRawRef;
    /**
     * 最近一次工具历史引用列表。
     */
    List<String> latestToolHistoryRefs;
    /**
     * 原始工具结果通道数据。
     */
    Map<String, Object> rawToolResultChannel;
    /**
     * 检索计划覆写配置。
     */
    Map<String, Object> retrievalPlanOverrides;
}
