package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;

import java.util.Map;

/**
 * 关系记忆检索接口，负责围绕会话关系状态召回情绪、偏好和陪伴类长期记忆，
 * 为陪伴型回复和关系推理提供上下文依据。
 */
public interface RelationalMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String semanticQuery, RelationalRuntimeState relationalState);
}
