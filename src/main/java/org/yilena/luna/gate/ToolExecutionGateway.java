package org.yilena.luna.gate;

import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.Resource;

/**
 * 工具执行网关接口，负责抽象不同工具执行链路的统一调用入口。
 */
public interface ToolExecutionGateway {

    /**
     * 执行指定工具资源，并返回统一的执行结果。
     */
    ExecutionResult executeTool(String sessionId, Resource resource, String argsJson);
}
