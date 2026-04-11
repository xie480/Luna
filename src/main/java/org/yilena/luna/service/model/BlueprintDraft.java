package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 蓝图草稿模型，负责承载主规划阶段在正式生成计划蓝图前整理出的目标、约束、证据和节点推理信息，
 * 为后续蓝图生成提供结构化输入。
 */
public class BlueprintDraft {
    /**
     * 归一化后的用户意图。
     */
    String normalizedUserIntent;
    /**
     * 明确提炼出的任务目标。
     */
    String explicitTaskGoal;
    /**
     * 任务相关的时间范围描述。
     */
    String timeScope;
    /**
     * 当前仍缺失的关键槽位列表。
     */
    List<String> missingSlots;
    /**
     * 当前任务的业务约束列表。
     */
    List<String> businessConstraints;
    /**
     * 当前所处阶段说明。
     */
    String currentStage;
    /**
     * 当前所处节点说明。
     */
    String currentNode;
    /**
     * 任务状态快照数据。
     */
    Map<String, Object> taskStateSnapshot;
    /**
     * 候选工作流提示信息。
     */
    List<Map<String, Object>> workflowHints;
    /**
     * 可用于规划的证据块列表。
     */
    List<Map<String, Object>> evidenceBlocks;
    /**
     * 各节点对应的规划理由说明。
     */
    Map<String, Object> rationaleByNode;
}
