package org.yilena.luna.service;

import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.service.model.RoundPipelineRequest;
import org.yilena.luna.service.model.RoundPipelineResult;
import org.yilena.luna.service.model.RoundToolSemanticRequest;

/**
 * 单轮流水线编排接口，负责串联工具语义解析与整轮执行流程，
 * 在一轮对话内协调上下文、工具和主模型阶段产出统一结果。
 */
public interface RoundPipelineOrchestrator {

    ToolSemanticResult resolveToolSemantic(RoundToolSemanticRequest request);

    RoundPipelineResult executeRound(RoundPipelineRequest request);
}
