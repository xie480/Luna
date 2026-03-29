package org.yilena.luna.gate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@Component
@RequiredArgsConstructor
public class McpToolExecutionGateway implements ToolExecutionGateway {

    private final ExecutionGate executionGate;
    private final ApprovalService approvalService;
    private final McpClientAdapter mcpClientAdapter;

    @Override
    public ExecutionResult executeTool(String sessionId, Resource resource, String argsJson) {
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

        String arguments = (argsJson == null || argsJson.isBlank()) ? "{}" : argsJson;
        if (!JsonSchemaValidator.validate(resource.getInputSchema(), arguments)) {
            return ExecutionResult.builder()
                    .status("error")
                    .message("args schema validation failed")
                    .rawResult("{\"status\":\"error\",\"message\":\"args schema validation failed\"}")
                    .data(Map.of("errorCode", "INVALID_ARGS"))
                    .build();
        }

        executionGate.check(resource);
        if (needApproval(resource)) {
            // This method interrupts execution by throwing NeedApprovalException.
            approvalService.createTaskAndInterrupt(sessionId, resource, arguments);
        }

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

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "success";
        }
        return status;
    }
}
