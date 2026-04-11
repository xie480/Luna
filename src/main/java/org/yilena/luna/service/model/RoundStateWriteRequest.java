package org.yilena.luna.service.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.context.model.SummaryResult;
import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.memory.model.OrchestrationDecision;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
/**
 * 回合状态写入请求模型，负责汇总单轮执行结束后需要落盘的状态、摘要和工具引用信息，
 * 供状态写回阶段统一持久化。
 */
public class RoundStateWriteRequest {

    /**
     * 当前会话标识。
     */
    private String sessionId;
    /**
     * 会话编排决策结果。
     */
    private OrchestrationDecision decision;
    /**
     * 结构化上下文包。
     */
    private StructuredContextPackage contextPackage;
    /**
     * 输入重构结果。
     */
    private InputReconstructionResult reconstruction;
    /**
     * 上下文重排结果。
     */
    private ContextRerankResult rerankResult;
    /**
     * 工具语义分析结果。
     */
    private ToolSemanticResult toolSemanticResult;
    /**
     * 摘要结果。
     */
    private SummaryResult summaryResult;
    /**
     * 最近一次上下文快照标识。
     */
    private String latestSnapshotId;
    /**
     * 最近一次工具原始结果引用。
     */
    private String latestToolRawRef;
    /**
     * 最近一次工具历史引用列表。
     */
    private List<String> latestToolHistoryRefs;
    /**
     * 原始工具结果通道数据。
     */
    private Map<String, Object> rawToolResultChannel;
    /**
     * 当前 RAG 查询语句。
     */
    private String ragQuery;
    /**
     * 当前记忆查询语句。
     */
    private String memoryQuery;
    /**
     * 当前 MCP 查询语句。
     */
    private String mcpQuery;
    /**
     * 检索计划覆写配置。
     */
    private Map<String, Object> retrievalPlanOverrides;
}
