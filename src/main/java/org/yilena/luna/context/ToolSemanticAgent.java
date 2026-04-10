package org.yilena.luna.context;

import org.yilena.luna.context.model.ToolSemanticResult;
import org.yilena.luna.enums.TaskRuntimeState;

/**
 * 工具语义代理接口，负责将工具原始输出翻译为结构化业务语义。
 */
public interface ToolSemanticAgent {
    /**
     * 把工具执行结果翻译为统一的语义结果对象。
     */
    ToolSemanticResult translate(String toolName,
                                 String toolDescription,
                                 String rawResult,
                                 TaskRuntimeState taskState,
                                 String currentNodeGoal);
}
