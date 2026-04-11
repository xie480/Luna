package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

import java.util.Map;

/**
 * 事件接入服务接口，负责把用户输入、工具结果、审批回执和系统事件统一封装为会话编排入口，
 * 保证不同来源的事件都按一致链路进入记忆与状态流转。
 */
public interface EventIngressService {

    OrchestrationDecision ingestUserInput(String sessionId, String userInput);

    OrchestrationDecision ingestUserInput(String sessionId, String userInput, String orchestrationSignal);

    OrchestrationDecision ingestToolResult(String sessionId, Map<String, Object> payload);

    OrchestrationDecision ingestApproval(String sessionId, Map<String, Object> payload);

    OrchestrationDecision ingestSystemEvent(String sessionId, String eventType, Map<String, Object> payload);

    void dispatchPendingEvents(int limit);
}
