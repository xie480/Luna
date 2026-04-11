package org.yilena.luna.state.model;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

@Value
@Builder
/**
 * 任务状态模型，负责描述当前会话任务目标、阶段推进、槽位确认与失败重试信息，
 * 为规划执行和恢复判断提供任务主状态快照。
 */
public class TaskState {
    /**
     * 当前任务标识。
     */
    String taskId;
    /**
     * 所属会话标识。
     */
    String sessionId;
    /**
     * 当前任务目标描述。
     */
    String objective;
    /**
     * 当前所处业务阶段。
     */
    String currentStage;
    /**
     * 当前所处节点标识或节点名称。
     */
    String currentNode;
    /**
     * 已确认的槽位参数集合。
     */
    Map<String, Object> confirmedSlots;
    /**
     * 尚待继续追问的关键信息列表。
     */
    List<String> pendingQuestions;
    /**
     * 已完成的步骤列表。
     */
    List<String> finishedSteps;
    /**
     * 已失败的步骤列表。
     */
    List<String> failedSteps;
    /**
     * 当前任务累计重试次数。
     */
    Integer retryCount;
    /**
     * 推荐的下一步动作提示。
     */
    String nextActionHint;
}
