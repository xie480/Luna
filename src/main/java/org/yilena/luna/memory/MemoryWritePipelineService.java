package org.yilena.luna.memory;

import org.yilena.luna.memory.model.StructuredContextPackage;

public interface MemoryWritePipelineService {
    void writeAfterTurn(String sessionId, String userInput, String assistantReply, StructuredContextPackage contextPackage);
}
