package org.yilena.luna.memory;

import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Map;

public interface TaskMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String semanticQuery, TaskRuntimeState taskState);
}
