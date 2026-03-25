package org.yilena.luna.service;

import org.yilena.luna.entity.PlanNode;
import org.yilena.luna.entity.PlanPhase;

import java.util.List;

/**
 * 阶段执行服务接口
 *
 * 职责：
 * - 负责单个阶段内的节点调度（串行 + 基于依赖的并行）
 * - 不感知全局计划状态，只处理阶段内的节点图
 * - 对外暴露阶段执行结果
 */
public interface PhaseExecutionService {

    /**
     * 执行指定阶段下的所有节点
     *
     * @param planId    计划 ID
     * @param phase     阶段实体
     * @param sessionId 会话 ID（用于 AgentService 工具调用）
     * @return 阶段执行结果 JSON 字符串
     */
    String executePhase(String planId, PlanPhase phase, String sessionId);

    /**
     * 按 DAG 拓扑排序节点，返回有序的执行批次
     * 同一批次内的节点可并行执行，批次间串行
     *
     * @param nodes 阶段内所有节点
     * @return 有序执行批次列表，每批次内为可并行节点
     */
    List<List<PlanNode>> resolveExecutionBatches(List<PlanNode> nodes);
}
