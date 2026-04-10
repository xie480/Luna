package org.yilena.luna.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "MCP JSON-RPC 接口", description = "接收标准 JSON-RPC 请求并分发到对应的 MCP 能力")
/**
 * MCP JSON-RPC 控制器，负责将标准 JSON-RPC 请求分发到对应的 MCP 服务能力。
 */
public class McpJsonRpcController {

    /**
     * MCP 服务，负责执行协议方法对应的业务逻辑。
     */
    private final McpService mcpService;

    @PostMapping("/rpc")
    /**
     * 接收 JSON-RPC 请求并根据方法名路由到对应的 MCP 能力。
     *
     * 该接口会统一处理请求参数提取、协议错误包装和异常兜底，保证前端始终拿到标准 JSON-RPC 响应结构。
     */
    @Operation(summary = "发起 JSON-RPC 调用", description = "接收标准 JSON-RPC 请求，并根据 method 分发到对应的 MCP 服务能力")
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

    /**
     * 构造 JSON-RPC 协议规定的错误响应体。
     */
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

    /**
     * 将原始参数对象安全转换为字符串键的 Map，便于后续统一取值。
     */
    private Map<String, Object> params(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    /**
     * 将任意参数转换为去除首尾空白的字符串。
     */
    private String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    /**
     * 当参数为空时返回默认值，避免协议字段缺失导致下游处理失败。
     */
    private String textOrDefault(Object raw, String defaultValue) {
        String value = text(raw);
        return value.isBlank() ? defaultValue : value;
    }
}
