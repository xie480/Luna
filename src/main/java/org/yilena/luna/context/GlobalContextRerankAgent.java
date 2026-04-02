package org.yilena.luna.context;

import org.yilena.luna.context.model.ContextRerankResult;
import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;
import org.yilena.luna.rag.models.RetrievalResponse;

import java.util.List;
import java.util.Map;

public interface GlobalContextRerankAgent {
    ContextRerankResult rerank(InputReconstructionResult reconstructionResult,
                               StructuredContextPackage contextPackage,
                               RetrievalResponse retrievalResponse,
                               List<Map<String, Object>> capabilityCandidates,
                               TaskRuntimeState taskState);
}

