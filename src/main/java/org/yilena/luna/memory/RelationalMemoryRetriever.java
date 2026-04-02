package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;

import java.util.Map;

public interface RelationalMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String semanticQuery, RelationalRuntimeState relationalState);
}
