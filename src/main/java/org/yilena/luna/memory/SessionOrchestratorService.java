package org.yilena.luna.memory;

import org.yilena.luna.memory.model.OrchestrationDecision;

/**
 * 会话编排服务接口，负责针对不同事件源推进会话任务态与关系态，
 * 并产出当前轮次应执行的编排决策结果。
 */
public interface SessionOrchestratorService {
    OrchestrationDecision onUserInput(String sessionId, String userInput);

    OrchestrationDecision onUserInput(String sessionId, String userInput, String orchestrationSignal);

    OrchestrationDecision onToolResult(String sessionId, String payloadJson);

    OrchestrationDecision onApproval(String sessionId, String payloadJson);

    OrchestrationDecision onSystemEvent(String sessionId, String eventType, String payloadJson);
}
