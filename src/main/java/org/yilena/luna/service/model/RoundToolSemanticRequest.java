package org.yilena.luna.service.model;

import lombok.Builder;
import lombok.Value;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 单轮工具语义请求模型，负责封装某次工具语义分析所需的会话上下文、候选资源和阶段信息，
 * 供工具语义节点统一处理。
 */
public class RoundToolSemanticRequest {
    /**
     * 当前会话标识。
     */
    String sessionId;
    /**
     * 结构化上下文包。
     */
    StructuredContextPackage contextPackage;
    /**
     * 当前任务运行状态。
     */
    TaskRuntimeState taskState;
    /**
     * 明确提炼出的任务目标。
     */
    String explicitTaskGoal;
    /**
     * 当前分析的工具名称。
     */
    String toolName;
    /**
     * 当前分析的工具描述。
     */
    String toolDescription;
    /**
     * 候选执行资源列表。
     */
    List<Resource> executionCandidates;
    /**
     * 工具上下文文本。
     */
    String toolContext;
    /**
     * 当前执行阶段名称。
     */
    String stage;
    /**
     * 原始工具结果通道数据。
     */
    Map<String, Object> rawToolResultChannel;
}
