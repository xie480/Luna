package org.yilena.luna.prompt.governance.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
/**
 * 提示词解析上下文模型，负责承载提示词解析阶段所需的会话、人格、场景和模型条件，
 * 作为提示词匹配与组装的统一上下文输入。
 */
public class PromptResolveContext {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 当前用户输入。
     */
    String userInput;
    /**
     * 当前策略标识。
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
     * 当前代理标识。
     */
    String agent;
    /**
     * 当前节点类型。
     */
    String nodeKind;
    /**
     * 当前任务状态。
     */
    String taskState;
    /**
     * 当前模型家族。
     */
    String modelFamily;
    /**
     * 手动指定的提示词键列表。
     */
    List<String> manualPromptKeys;
}
