package org.yilena.luna.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yilena.luna.constants.JsonFieldConstants;
import org.yilena.luna.constants.McpProtocolConstants;
import org.yilena.luna.enums.JsonRpcErrorCodeEnum;
import org.yilena.luna.service.McpService;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class McpJsonRpcController {

    private final McpService mcpService;

    @PostMapping("/rpc")
    public Map<String, Object> invoke(@RequestBody Map<String, Object> body) {
        Object id = body == null ? null : body.get(JsonFieldConstants.ID);
        String method = text(body == null ? null : body.get(JsonFieldConstants.METHOD));
        Map<String, Object> params = params(body == null ? null : body.get(JsonFieldConstants.PARAMS));
        if (method.isBlank()) {
            return error(id, JsonRpcErrorCodeEnum.INVALID_REQUEST, "Invalid Request: method is required");
        }
        try {
            Object result = switch (method) {
                case McpProtocolConstants.METHOD_TOOLS_LIST, McpProtocolConstants.METHOD_MCP_TOOLS_LIST ->
                        mcpService.listTools(text(params.get("serverCode")));
                case McpProtocolConstants.METHOD_TOOLS_CALL, McpProtocolConstants.METHOD_MCP_TOOLS_CALL -> mcpService.callTool(
                        text(params.get("serverCode")),
                        text(params.get("toolName")),
                        textOrDefault(params.get("argumentsJson"), McpProtocolConstants.DEFAULT_ARGUMENTS_JSON)
                );
                case McpProtocolConstants.METHOD_PROMPTS_LIST, McpProtocolConstants.METHOD_MCP_PROMPTS_LIST ->
                        mcpService.listPrompts(text(params.get("serverCode")));
                case McpProtocolConstants.METHOD_PROMPTS_GET, McpProtocolConstants.METHOD_MCP_PROMPTS_GET -> mcpService.getPrompt(
                        text(params.get("serverCode")),
                        text(params.get("promptName")),
                        textOrDefault(params.get("argumentsJson"), McpProtocolConstants.DEFAULT_ARGUMENTS_JSON)
                );
                case McpProtocolConstants.METHOD_RESOURCES_LIST, McpProtocolConstants.METHOD_MCP_RESOURCES_LIST ->
                        mcpService.listResources(text(params.get("serverCode")));
                case McpProtocolConstants.METHOD_RESOURCES_READ, McpProtocolConstants.METHOD_MCP_RESOURCES_READ -> mcpService.readResource(
                        text(params.get("serverCode")),
                        text(params.get("resourceUri"))
                );
                default -> null;
            };
            if (result == null) {
                return error(id, JsonRpcErrorCodeEnum.METHOD_NOT_FOUND, "Method not found: " + method);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put(JsonFieldConstants.JSON_RPC, McpProtocolConstants.JSON_RPC_VERSION);
            response.put(JsonFieldConstants.ID, id);
            response.put(JsonFieldConstants.RESULT, result);
            return response;
        } catch (Exception e) {
            return error(id, JsonRpcErrorCodeEnum.INTERNAL_ERROR, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> error(Object id, JsonRpcErrorCodeEnum errorCode, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(JsonFieldConstants.JSON_RPC, McpProtocolConstants.JSON_RPC_VERSION);
        out.put(JsonFieldConstants.ID, id);
        out.put(JsonFieldConstants.ERROR, Map.of(
                JsonFieldConstants.CODE, errorCode.getCode(),
                JsonFieldConstants.MESSAGE, message == null ? "" : message
        ));
        return out;
    }

    private Map<String, Object> params(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private String textOrDefault(Object raw, String defaultValue) {
        String value = text(raw);
        return value.isBlank() ? defaultValue : value;
    }
}
