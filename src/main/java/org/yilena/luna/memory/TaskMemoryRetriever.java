package org.yilena.luna.memory;

import java.util.Map;

public interface TaskMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String userInput);
}
