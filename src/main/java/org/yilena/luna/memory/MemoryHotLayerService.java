package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Map;

/**
 * 记忆热层服务接口，负责维护会话级缓存、上下文缓存和待执行工具调用的临时热数据，
 * 用于降低重复装载成本并支撑异步任务回写。
 */
public interface MemoryHotLayerService {

    Map<String, Object> getSessionCache(String sessionId);

    void putSessionCache(String sessionId, Map<String, Object> payload);

    Map<String, Object> getWorkingMemoryCache(String sessionId);

    void putWorkingMemoryCache(String sessionId, Map<String, Object> payload);

    StructuredContextPackage getCompiledContextCache(String sessionId,
                                                     String userInput,
                                                     TaskRuntimeState taskState,
                                                     RelationalRuntimeState relationalState);

    void putCompiledContextCache(String sessionId,
                                 String userInput,
                                 TaskRuntimeState taskState,
                                 RelationalRuntimeState relationalState,
                                 StructuredContextPackage contextPackage);

    boolean tryDedupeEvent(String sessionId, String eventType, String payloadJson);

    void putPendingToolCall(String sessionId, String taskId, Map<String, Object> payload);

    Map<String, Object> getLatestPendingToolCall(String sessionId);

    void clearPendingToolCall(String sessionId, String taskId);

    void clearPendingToolCallByTaskId(String taskId);
}
