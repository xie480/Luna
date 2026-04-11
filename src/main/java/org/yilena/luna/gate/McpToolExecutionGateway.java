package org.yilena.luna.gate;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.common.utils.JsonSchemaValidator;
import org.yilena.luna.entity.ExecutionResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.Resource;
import org.yilena.luna.enums.ResourceType;
import org.yilena.luna.enums.Sensitivity;
import org.yilena.luna.service.ApprovalService;

import java.util.Collections;
import java.util.Map;

/**
 * MCP 工具执行网关，负责完成工具参数校验、权限闸门检查、审批中断和最终 MCP 调用。
 */
@Slf4j
@Component
public class McpToolExecutionGateway implements ToolExecutionGateway {

    /**
     * 执行权限闸门，用于拦截高风险工具调用。
     */
    private final ExecutionGate executionGate;

    /**
     * 审批服务，用于在需要人工审批时创建任务并中断执行。
     */
    private final ApprovalService approvalService;

    /**
     * MCP 客户端适配器提供器，用于延迟获取可用的工具调用实现。
     */
    private final ObjectProvider<McpClientAdapter> mcpClientAdapterProvider;

    public McpToolExecutionGateway(
            ExecutionGate executionGate,
            @Lazy ApprovalService approvalService,
            ObjectProvider<McpClientAdapter> mcpClientAdapterProvider
    ) {
        this.executionGate = executionGate;
        this.approvalService = approvalService;
        this.mcpClientAdapterProvider = mcpClientAdapterProvider;
    }

    @Override
    public ExecutionResult executeTool(String sessionId, Resource resource, String argsJson) {
        /**
         * 先校验资源是否存在且类型为 TOOL，避免非工具资源进入后续执行链路。
         */
        if (resource == null) {
            return ExecutionResult.builder()
                    .status("error")
                    .message("resource is null")
                    .rawResult("{\"status\":\"error\",\"message\":\"resource is null\"}")
                    .data(Map.of("errorCode", "RESOURCE_NULL"))
                    .build();
        }

        if (!ResourceType.TOOL.equals(resource.getType())) {
            return ExecutionResult.builder()
                    .status("error")
                    .message("resource is not TOOL")
                    .rawResult("{\"status\":\"error\",\"message\":\"resource is not TOOL\"}")
                    .data(Map.of("errorCode", "INVALID_RESOURCE_TYPE"))
                    .build();
        }

        /**
         * 对参数执行 Schema 校验，确保工具只接收符合声明结构的输入。
         */
        String arguments = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        if (!JsonSchemaValidator.validate(resource.getInputSchema(), arguments)) {
            return ExecutionResult.builder()
                    .status("error")
                    .message("args schema validation failed")
                    .rawResult("{\"status\":\"error\",\"message\":\"args schema validation failed\"}")
                    .data(Map.of("errorCode", "INVALID_ARGS"))
                    .build();
        }

        /**
         * 参数通过后先经过执行权限闸门，必要时再进入审批中断流程。
         */
        executionGate.check(resource);
        if (needApproval(resource)) {
            approvalService.createTaskAndInterrupt(sessionId, resource, arguments);
        }

        /**
         * 获取 MCP 客户端并执行真实工具调用，不可用时返回统一错误结果。
         */
        McpClientAdapter mcpClientAdapter = mcpClientAdapterProvider.getIfAvailable();
        if (mcpClientAdapter == null) {
            return ExecutionResult.builder()
                    .status("error")
                    .message("mcp client unavailable")
                    .rawResult("{\"status\":\"error\",\"message\":\"mcp client unavailable\"}")
                    .data(Map.of("errorCode", "MCP_CLIENT_UNAVAILABLE"))
                    .build();
        }

        /**
         * 将 MCP 原始结果统一规范为 ExecutionResult，便于上层链路统一消费。
         */
        McpToolCallResult result = mcpClientAdapter.callTool(resource.getServerCode(), resource.getName(), arguments);
        Map<String, Object> data = result.getData() == null ? Collections.emptyMap() : result.getData();
        String status = normalizeStatus(result.getStatus());
        String message = String.valueOf(data.getOrDefault("message", ""));

        return ExecutionResult.builder()
                .status(status)
                .message(message)
                .data(data)
                .rawResult(result.getRawResult())
                .build();
    }

    /**
     * 判断当前工具是否需要进入审批流程。
     */
    private boolean needApproval(Resource resource) {
        if (Boolean.TRUE.equals(resource.getRequiresApproval())) {
            return true;
        }
        if (resource.getSensitivity() == null) {
            return false;
        }
        return resource.getSensitivity() == Sensitivity.MEDIUM
                || resource.getSensitivity() == Sensitivity.HIGH;
    }

    /**
     * 规范化工具返回状态，缺省时按成功处理。
     */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "success";
        }
        return status;
    }
}
