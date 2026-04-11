package org.yilena.luna.memory;

import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Map;

/**
 * 任务记忆检索接口，负责围绕任务状态召回工作记忆、语义事实和历史执行痕迹，
 * 为规划、工具决策和任务回复阶段提供任务侧上下文。
 */
public interface TaskMemoryRetriever {
    Map<String, Object> retrieve(String sessionId, String semanticQuery, TaskRuntimeState taskState);
}
