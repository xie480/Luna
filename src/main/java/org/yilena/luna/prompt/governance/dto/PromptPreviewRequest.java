package org.yilena.luna.prompt.governance.dto;

import lombok.Data;

import java.util.List;

@Data
/**
 * 提示词预览请求模型，负责承载预览匹配或预览组装阶段需要的上下文条件，
 * 供治理后台模拟运行时解析场景使用。
 */
public class PromptPreviewRequest {
    /**
     * 预览所属会话标识。
     */
    private String sessionId;
    /**
     * 预览时的用户输入。
     */
    private String userInput;
    /**
     * 预览时指定的策略标识。
     */
    private String policyId;
    /**
     * 预览时指定的人格标识。
     */
    private String personaId;
    /**
     * 预览时指定的场景标识。
     */
    private String sceneId;
    /**
     * 预览时指定的代理或执行主体标识。
     */
    private String agent;
    /**
     * 预览时指定的节点类型。
     */
    private String nodeKind;
    /**
     * 预览时指定的任务状态。
     */
    private String taskState;
    /**
     * 预览时指定的模型家族。
     */
    private String modelFamily;
    /**
     * 手动指定的提示词键列表。
     */
    private List<String> manualPromptKeys;
}
