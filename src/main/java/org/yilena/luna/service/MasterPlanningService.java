package org.yilena.luna.service;

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
     * @param userGoal  用户目标
     * @return 蓝图对象（Map）
     */
    Map<String, Object> generateBlueprint(String planId, String sessionId, String userGoal);
}
