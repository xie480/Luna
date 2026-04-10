package org.yilena.luna.context;

import org.yilena.luna.context.model.InputReconstructionResult;
import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.StructuredContextPackage;

/**
 * 输入重构代理接口，负责将用户原始输入还原为结构化任务意图。
 */
public interface InputReconstructionAgent {
    /**
     * 重构当前输入的标准化意图结果。
     */
    InputReconstructionResult reconstruct(String sessionId,
                                          String userInput,
                                          StructuredContextPackage contextPackage,
                                          TaskRuntimeState taskState,
                                          RelationalRuntimeState relationalState);
}
