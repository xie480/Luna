package org.yilena.luna.service;

import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.RoundToolSemanticRequest;

public interface RoundPipelineOrchestrator {

    ToolSemanticResult resolveToolSemantic(RoundToolSemanticRequest request);

    RoundPipelineResult executeRound(RoundPipelineRequest request);
}

