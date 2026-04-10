package org.yilena.luna.memory.model;

import lombok.Builder;
import lombok.Data;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.state.model.ContextState;
import org.yilena.luna.state.model.RecoveryState;
import org.yilena.luna.state.model.RetrievalState;
import org.yilena.luna.state.model.TaskState;
import org.yilena.luna.state.model.ToolState;

import java.util.List;
import java.util.Map;

/**
 * 该模型用于承载记忆编排后的结构化上下文，统一汇总运行态、检索结果和多类状态实体。
 */
@Data
@Builder
public class StructuredContextPackage {
    /**
     * 当前会话标识。
     */
    private String sessionId;
    /**
     * 当前任务运行状态。
     */
    private TaskRuntimeState taskState;
    /**
     * 当前关系型运行状态。
     */
    private RelationalRuntimeState relationalState;
    /**
     * 运行时扩展上下文数据。
     */
    private Map<String, Object> runtime;
    /**
     * 当前任务上下文数据。
     */
    private Map<String, Object> taskContext;
    /**
     * 关系型记忆上下文数据。
     */
    private Map<String, Object> relationalContext;
    /**
     * 最近消息列表。
     */
    private List<Map<String, Object>> recentMessages;
    /**
     * 可用能力候选集合。
     */
    private List<Map<String, Object>> capabilityCandidates;
    /**
     * 当前生效的提示词策略信息。
     */
    private Map<String, Object> promptPolicy;
    /**
     * 上下文各章节的 token 预算方案。
     */
    private Map<String, Integer> tokenBudgetPlan;
    /**
     * 任务状态实体快照。
     */
    private TaskState taskStateEntity;
    /**
     * 检索状态实体快照。
     */
    private RetrievalState retrievalState;
    /**
     * 工具状态实体快照。
     */
    private ToolState toolState;
    /**
     * 上下文状态实体快照。
     */
    private ContextState contextState;
    /**
     * 恢复状态实体快照。
     */
    private RecoveryState recoveryState;
}
