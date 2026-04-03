package org.yilena.luna.service;

import org.yilena.luna.context.model.InputReconstructionResult;

import java.util.List;
import java.util.Map;

/**
 * Master Planner 服务
 * 负责由大模型一次性生成全局蓝图（phase/node/edge）
 */
public interface MasterPlanningService {

    /**
     * 生成计划蓝图
     *
     * @param planId    计划ID
     * @param sessionId 会话ID
     * @param reconstructedGoal  输入重构后的明确任务目标
     * @return 蓝图对象（Map）
     */
    Map<String, Object> generateBlueprint(String planId,
                                          String sessionId,
                                          String reconstructedGoal,
                                          InputReconstructionResult reconstructionResult,
                                          List<Map<String, Object>> knowledgeEvidence,
                                          List<Map<String, Object>> workflowHints);
}
