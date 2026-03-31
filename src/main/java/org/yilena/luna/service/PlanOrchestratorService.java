package org.yilena.luna.service;

/**
 * OpenClaw 计划编排服务（MVP）
 * 最小闭环：
 * 1) 创建并执行计划
 * 2) 执行阶段
 * 3) 收尾并生成报告
 */
public interface PlanOrchestratorService {

    /**
     * 创建并执行计划（MVP）
     * @param sessionId 稳定会话ID（建议 JWT jti）
     * @param userGoal 用户目标
     * @return 结果JSON字符串
     */
    String createAndRunPlan(String sessionId, String userGoal);

    default String createAndRunPlan(String sessionId, String userGoal, boolean callbackToChat) {
        return createAndRunPlan(sessionId, userGoal);
    }

    /**
     * 执行单阶段
     * @param planId 计划ID
     * @param phaseId 阶段ID
     * @return 结果JSON字符串
     */
    String runPhase(String planId, String phaseId);

    /**
     * 收尾并生成报告
     * @param planId 计划ID
     * @return 结果JSON字符串
     */
    String finalizeAndReport(String planId);

    /**
     * 获取计划可视化图谱快照（phase -> node）
     * @param planId 计划ID
     * @return 结果JSON字符串
     */
    String getPlanGraph(String planId);
}
