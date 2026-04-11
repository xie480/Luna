package org.yilena.luna.memory;

import org.yilena.luna.enums.RelationalRuntimeState;
import org.yilena.luna.enums.TaskRuntimeState;
import org.yilena.luna.memory.model.ContextCompileOptions;
import org.yilena.luna.memory.model.StructuredContextPackage;

/**
 * 上下文编译服务接口，负责将会话运行态、状态存储和多路记忆检索结果组装为统一的结构化上下文包，
 * 供后续规划、工具决策与主模型生成阶段复用。
 */
public interface ContextCompilerService {
    default StructuredContextPackage compile(String sessionId,
                                             String userInput,
                                             TaskRuntimeState taskState,
                                             RelationalRuntimeState relationalState) {
        return compile(sessionId, userInput, taskState, relationalState, ContextCompileOptions.auto());
    }

    StructuredContextPackage compile(String sessionId,
                                     String userInput,
                                     TaskRuntimeState taskState,
                                     RelationalRuntimeState relationalState,
                                     ContextCompileOptions options);
}
