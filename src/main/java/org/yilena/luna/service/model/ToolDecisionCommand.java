package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.List;

@Value
@Builder
/**
 * 工具决策命令模型，负责封装工具决策阶段所需的输入、运行状态、候选资源和治理信息，
 * 作为 Agent 工具决策服务的统一入参。
 */
public class ToolDecisionCommand {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 用户原始输入文本。
     */
    String rawUserInput;
    /**
     * 用于工具决策的输入文本。
     */
    String toolDecisionInput;
    /**
     * 当前生效的提示策略标识。
     */
    String policyId;
    /**
     * 当前人格标识。
     */
    String personaId;
    /**
     * 当前场景标识。
     */
    String sceneId;
    /**
     * 当前任务运行状态。
     */
    TaskRuntimeState taskState;
    /**
     * 当前关系运行状态。
     */
    RelationalRuntimeState relationalState;
    /**
     * 模型家族标识。
     */
    String modelFamily;
    /**
     * 手动指定的提示词键集合。
     */
    List<String> manualPromptKeys;
    /**
     * 当前候选执行资源集合。
     */
    List<Resource> executionCandidates;
    /**
     * 治理后输入签名。
     */
    String governedInputSignature;
    /**
     * 已组装的工具决策上下文文本。
     */
    String assembledDecisionContext;
}
