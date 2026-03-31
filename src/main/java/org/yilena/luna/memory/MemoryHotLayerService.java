package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.Map;

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
