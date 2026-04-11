package org.yilena.luna.service;

import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.StateDrivenContextPipelineRequest;

/**
 * 状态驱动上下文流水线接口，负责依据触发来源和轮次请求执行一轮上下文驱动流程，
 * 让状态变化能够稳定映射到统一的回合处理链路。
 */
public interface StateDrivenContextPipeline {

    RoundPipelineResult run(StateDrivenContextPipelineRequest request);
}
