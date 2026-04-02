package org.yilena.luna.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
        Object id = body == null ? null : body.get("id");
        String method = text(body == null ? null : body.get("method"));
        Map<String, Object> params = params(body == null ? null : body.get("params"));
        if (method.isBlank()) {
            return error(id, -32600, "Invalid Request: method is required");
        }
        try {
            Object result = switch (method) {
                case "tools/list", "mcp.tools.list" -> mcpService.listTools(text(params.get("serverCode")));
                case "tools/call", "mcp.tools.call" -> mcpService.callTool(
                        text(params.get("serverCode")),
                        text(params.get("toolName")),
                        textOrDefault(params.get("argumentsJson"), "{}")
                );
                case "prompts/list", "mcp.prompts.list" -> mcpService.listPrompts(text(params.get("serverCode")));
                case "prompts/get", "mcp.prompts.get" -> mcpService.getPrompt(
                        text(params.get("serverCode")),
                        text(params.get("promptName")),
                        textOrDefault(params.get("argumentsJson"), "{}")
                );
                case "resources/list", "mcp.resources.list" -> mcpService.listResources(text(params.get("serverCode")));
                case "resources/read", "mcp.resources.read" -> mcpService.readResource(
                        text(params.get("serverCode")),
                        text(params.get("resourceUri"))
                );
                default -> null;
            };
            if (result == null) {
                return error(id, -32601, "Method not found: " + method);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            return response;
        } catch (Exception e) {
            return error(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", message == null ? "" : message));
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
