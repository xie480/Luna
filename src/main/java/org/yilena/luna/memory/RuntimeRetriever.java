package org.yilena.luna.memory;

import java.util.Map;

public interface RuntimeRetriever {
    Map<String, Object> retrieve(String sessionId);
}
