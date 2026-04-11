package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;

import java.util.Map;

/**
 * 响应综合策略服务接口，负责根据任务态、关系态和多路上下文生成回复综合策略，
 * 指导后续主模型如何平衡任务完成、关系表达与风格控制。
 */
public interface ResponseSynthesizerService {
    Map<String, Object> buildSynthesisPolicy(TaskRuntimeState taskState,
                                             RelationalRuntimeState relationalState,
                                             Map<String, Object> taskContext,
                                             Map<String, Object> relationalContext,
                                             Map<String, Object> socialDraft);
}
