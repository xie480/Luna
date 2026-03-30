package org.yilena.luna.memory;

import java.util.Map;

public interface RelationalMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String userInput);
}
