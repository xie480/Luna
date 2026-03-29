package org.yilena.luna.adapter.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yilena.luna.adapter.McpClientAdapter;
import org.yilena.luna.constants.McpConstant;
import org.yilena.luna.entity.McpPromptCatalog;
import org.yilena.luna.entity.McpPromptDescriptor;
import org.yilena.luna.entity.McpPromptResult;
import org.yilena.luna.entity.McpResourceCatalog;
import org.yilena.luna.entity.McpResourceDescriptor;
import org.yilena.luna.entity.McpResourceResult;
import org.yilena.luna.entity.McpToolCallResult;
import org.yilena.luna.entity.McpToolCatalog;
import org.yilena.luna.entity.McpToolDescriptor;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.executor.ReflectionToolExecutor;
import org.yilena.luna.mapper.McpPromptCatalogMapper;
import org.yilena.luna.mapper.McpResourceCatalogMapper;
import org.yilena.luna.mapper.McpToolCatalogMapper;
import org.yilena.luna.mapper.McpToolImplMappingMapper;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalMcpClientAdapter implements McpClientAdapter {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final ReflectionToolExecutor reflectionToolExecutor;
    private final ObjectMapper objectMapper;

    @Override
    public List<McpToolDescriptor> listTools(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        List<McpToolCatalog> rows = toolCatalogMapper.selectList(
                new LambdaQueryWrapper<McpToolCatalog>()
                        .eq(McpToolCatalog::getServerCode, targetServer)
                        .eq(McpToolCatalog::getEnabled, true)
        );
        return rows.stream().map(this::toToolDescriptor).toList();
    }

    @Override
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) {
        String targetServer = normalizeServerCode(serverCode);
        McpToolImplMapping mapping = toolImplMappingMapper.findEnabledMapping(targetServer, toolName);
        if (mapping == null) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .toolName(toolName)
                    .rawResult(errorResult("TOOL_MAPPING_NOT_FOUND", "No enabled impl mapping found"))
                    .data(Map.of("errorCode", "TOOL_MAPPING_NOT_FOUND", "message", "No enabled impl mapping found"))
                    .build();
        }

        String implType = mapping.getImplType() == null ? "SPRING_BEAN" : mapping.getImplType().trim().toUpperCase();
        if (!"SPRING_BEAN".equals(implType)) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .toolName(toolName)
                    .rawResult(errorResult("UNSUPPORTED_IMPL", "Only SPRING_BEAN impl is supported currently"))
                    .data(Map.of("errorCode", "UNSUPPORTED_IMPL", "message", "Only SPRING_BEAN impl is supported currently"))
                    .build();
        }

        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        String rawResult = reflectionToolExecutor.executeInternal(mapping.getBeanName(), mapping.getMethodName(), args);
        Map<String, Object> data = parseMap(rawResult);
        String status = parseStatus(data);

        return McpToolCallResult.builder()
                .status(status)
                .serverCode(targetServer)
                .toolName(toolName)
                .rawResult(rawResult)
                .data(data)
                .build();
    }

    @Override
    public List<McpPromptDescriptor> listPrompts(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        List<McpPromptCatalog> rows = promptCatalogMapper.selectList(
                new LambdaQueryWrapper<McpPromptCatalog>()
                        .eq(McpPromptCatalog::getServerCode, targetServer)
                        .eq(McpPromptCatalog::getEnabled, true)
        );
        return rows.stream().map(this::toPromptDescriptor).toList();
    }

    @Override
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) {
        String targetServer = normalizeServerCode(serverCode);
        McpPromptCatalog prompt = promptCatalogMapper.selectOne(
                new LambdaQueryWrapper<McpPromptCatalog>()
                        .eq(McpPromptCatalog::getServerCode, targetServer)
                        .eq(McpPromptCatalog::getPromptName, promptName)
                        .eq(McpPromptCatalog::getEnabled, true)
                        .last("LIMIT 1")
        );
        if (prompt == null) {
            return McpPromptResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .promptName(promptName)
                    .promptContent(Map.of("errorCode", "PROMPT_NOT_FOUND", "message", "Prompt not found"))
                    .build();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("promptName", prompt.getPromptName());
        result.put("title", prompt.getTitle());
        result.put("description", prompt.getDescription());
        result.put("rawPayload", prompt.getRawPayload());
        result.put("arguments", parseMap(argumentsJson));

        return McpPromptResult.builder()
                .status("success")
                .serverCode(targetServer)
                .promptName(promptName)
                .promptContent(result)
                .build();
    }

    @Override
    public List<McpResourceDescriptor> listResources(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        List<McpResourceCatalog> rows = resourceCatalogMapper.selectList(
                new LambdaQueryWrapper<McpResourceCatalog>()
                        .eq(McpResourceCatalog::getServerCode, targetServer)
                        .eq(McpResourceCatalog::getEnabled, true)
        );
        return rows.stream().map(this::toResourceDescriptor).toList();
    }

    @Override
    public McpResourceResult readResource(String serverCode, String resourceUri) {
        String targetServer = normalizeServerCode(serverCode);
        McpResourceCatalog row = resourceCatalogMapper.selectOne(
                new LambdaQueryWrapper<McpResourceCatalog>()
                        .eq(McpResourceCatalog::getServerCode, targetServer)
                        .eq(McpResourceCatalog::getResourceUri, resourceUri)
                        .eq(McpResourceCatalog::getEnabled, true)
                        .last("LIMIT 1")
        );
        if (row == null) {
            return McpResourceResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .resourceUri(resourceUri)
                    .data(Map.of("errorCode", "RESOURCE_NOT_FOUND", "message", "Resource not found"))
                    .build();
        }
        return McpResourceResult.builder()
                .status("success")
                .serverCode(targetServer)
                .resourceUri(resourceUri)
                .mimeType(row.getMimeType())
                .data(row.getRawPayload() == null ? Collections.emptyMap() : row.getRawPayload())
                .build();
    }

    private String normalizeServerCode(String serverCode) {
        if (serverCode == null || serverCode.isBlank()) {
            return McpConstant.LOCAL_SERVER_CODE;
        }
        return serverCode.trim();
    }

    private McpToolDescriptor toToolDescriptor(McpToolCatalog row) {
        return McpToolDescriptor.builder()
                .serverCode(row.getServerCode())
                .toolName(row.getToolName())
                .title(row.getTitle())
                .description(row.getDescription())
                .inputSchema(row.getInputSchema())
                .outputSchema(row.getOutputSchema())
                .requiresApproval(row.getRequiresApproval())
                .sensitivity(row.getSensitivity())
                .version(row.getVersion())
                .build();
    }

    private McpPromptDescriptor toPromptDescriptor(McpPromptCatalog row) {
        return McpPromptDescriptor.builder()
                .serverCode(row.getServerCode())
                .promptName(row.getPromptName())
                .title(row.getTitle())
                .description(row.getDescription())
                .argumentsSchema(row.getArgumentsSchema())
                .version(row.getVersion())
                .build();
    }

    private McpResourceDescriptor toResourceDescriptor(McpResourceCatalog row) {
        return McpResourceDescriptor.builder()
                .serverCode(row.getServerCode())
                .resourceUri(row.getResourceUri())
                .name(row.getName())
                .description(row.getDescription())
                .mimeType(row.getMimeType())
                .annotations(row.getAnnotations())
                .build();
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw", json);
        }
    }

    private String parseStatus(Map<String, Object> data) {
        Object status = data.get("status");
        if (status == null) {
            return "success";
        }
        String text = String.valueOf(status);
        return text.isBlank() ? "success" : text;
    }

    private String errorResult(String code, String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "error",
                    "errorCode", code,
                    "message", message
            ));
        } catch (Exception e) {
            log.warn("build error result failed", e);
            return "{\"status\":\"error\"}";
        }
    }
}
