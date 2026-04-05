package org.yilena.luna.service;

import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;

public interface StateDrivenContextPipeline {

    RoundPipelineResult run(StateDrivenContextPipelineRequest request);
}
