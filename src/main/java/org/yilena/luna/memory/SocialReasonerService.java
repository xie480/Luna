package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;

import java.util.Map;

public interface SocialReasonerService {
    Map<String, Object> buildRelationalDraft(String sessionId,
                                             String userInput,
                                             RelationalRuntimeState relationalState,
                                             Map<String, Object> relationalContext);
}
