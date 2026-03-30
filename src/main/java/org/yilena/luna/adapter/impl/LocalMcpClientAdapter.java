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
/**
 * LocalMcpClientAdapter ??
 */
public class LocalMcpClientAdapter implements McpClientAdapter {

    private final McpToolCatalogMapper toolCatalogMapper; // 声明成员字段
    private final McpPromptCatalogMapper promptCatalogMapper; // 声明成员字段
    private final McpResourceCatalogMapper resourceCatalogMapper; // 声明成员字段
    private final McpToolImplMappingMapper toolImplMappingMapper; // 声明成员字段
    private final ReflectionToolExecutor reflectionToolExecutor; // 声明成员字段
    private final ObjectMapper objectMapper; // 声明成员字段

    @Override // 声明注解
    public List<McpToolDescriptor> listTools(String serverCode) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        List<McpToolCatalog> rows = toolCatalogMapper.selectList( // 执行赋值操作
                new LambdaQueryWrapper<McpToolCatalog>() // 执行当前逻辑
                        .eq(McpToolCatalog::getServerCode, targetServer) // 执行当前逻辑
                        .eq(McpToolCatalog::getEnabled, true) // 执行当前逻辑
        ); // 执行语句逻辑
        return rows.stream().map(this::toToolDescriptor).toList(); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpToolCallResult callTool(String serverCode, String toolName, String argumentsJson) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        McpToolImplMapping mapping = toolImplMappingMapper.findEnabledMapping(targetServer, toolName); // 执行赋值操作
        if (mapping == null) { // 进行条件判断
            return McpToolCallResult.builder() // 返回处理结果
                    .status("error") // 执行当前逻辑
                    .serverCode(targetServer) // 执行当前逻辑
                    .toolName(toolName) // 执行当前逻辑
                    .rawResult(errorResult("TOOL_MAPPING_NOT_FOUND", "No enabled impl mapping found")) // 执行当前逻辑
                    .data(Map.of("errorCode", "TOOL_MAPPING_NOT_FOUND", "message", "No enabled impl mapping found")) // 执行当前逻辑
                    .build(); // 执行语句逻辑
        } // 结束当前代码块

        String implType = mapping.getImplType() == null ? "SPRING_BEAN" : mapping.getImplType().trim().toUpperCase(); // 执行赋值操作
        if (!"SPRING_BEAN".equals(implType)) { // 进行条件判断
            return McpToolCallResult.builder() // 返回处理结果
                    .status("error") // 执行当前逻辑
                    .serverCode(targetServer) // 执行当前逻辑
                    .toolName(toolName) // 执行当前逻辑
                    .rawResult(errorResult("UNSUPPORTED_IMPL", "Only SPRING_BEAN impl is supported currently")) // 执行当前逻辑
                    .data(Map.of("errorCode", "UNSUPPORTED_IMPL", "message", "Only SPRING_BEAN impl is supported currently")) // 执行当前逻辑
                    .build(); // 执行语句逻辑
        } // 结束当前代码块

        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson; // 执行赋值操作
        String rawResult = reflectionToolExecutor.executeInternal(mapping.getBeanName(), mapping.getMethodName(), args); // 执行赋值操作
        Map<String, Object> data = parseMap(rawResult); // 执行赋值操作
        String status = parseStatus(data); // 执行赋值操作

        return McpToolCallResult.builder() // 返回处理结果
                .status(status) // 执行当前逻辑
                .serverCode(targetServer) // 执行当前逻辑
                .toolName(toolName) // 执行当前逻辑
                .rawResult(rawResult) // 执行当前逻辑
                .data(data) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public List<McpPromptDescriptor> listPrompts(String serverCode) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        List<McpPromptCatalog> rows = promptCatalogMapper.selectList( // 执行赋值操作
                new LambdaQueryWrapper<McpPromptCatalog>() // 执行当前逻辑
                        .eq(McpPromptCatalog::getServerCode, targetServer) // 执行当前逻辑
                        .eq(McpPromptCatalog::getEnabled, true) // 执行当前逻辑
        ); // 执行语句逻辑
        return rows.stream().map(this::toPromptDescriptor).toList(); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpPromptResult getPrompt(String serverCode, String promptName, String argumentsJson) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        McpPromptCatalog prompt = promptCatalogMapper.selectOne( // 执行赋值操作
                new LambdaQueryWrapper<McpPromptCatalog>() // 执行当前逻辑
                        .eq(McpPromptCatalog::getServerCode, targetServer) // 执行当前逻辑
                        .eq(McpPromptCatalog::getPromptName, promptName) // 执行当前逻辑
                        .eq(McpPromptCatalog::getEnabled, true) // 执行当前逻辑
                        .last("LIMIT 1") // 执行当前逻辑
        ); // 执行语句逻辑
        if (prompt == null) { // 进行条件判断
            return McpPromptResult.builder() // 返回处理结果
                    .status("error") // 执行当前逻辑
                    .serverCode(targetServer) // 执行当前逻辑
                    .promptName(promptName) // 执行当前逻辑
                    .promptContent(Map.of("errorCode", "PROMPT_NOT_FOUND", "message", "Prompt not found")) // 执行当前逻辑
                    .build(); // 执行语句逻辑
        } // 结束当前代码块

        Map<String, Object> result = new LinkedHashMap<>(); // 执行赋值操作
        result.put("promptName", prompt.getPromptName()); // 执行语句逻辑
        result.put("title", prompt.getTitle()); // 执行语句逻辑
        result.put("description", prompt.getDescription()); // 执行语句逻辑
        result.put("rawPayload", prompt.getRawPayload()); // 执行语句逻辑
        result.put("arguments", parseMap(argumentsJson)); // 执行语句逻辑

        return McpPromptResult.builder() // 返回处理结果
                .status("success") // 执行当前逻辑
                .serverCode(targetServer) // 执行当前逻辑
                .promptName(promptName) // 执行当前逻辑
                .promptContent(result) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    @Override // 声明注解
    public List<McpResourceDescriptor> listResources(String serverCode) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        List<McpResourceCatalog> rows = resourceCatalogMapper.selectList( // 执行赋值操作
                new LambdaQueryWrapper<McpResourceCatalog>() // 执行当前逻辑
                        .eq(McpResourceCatalog::getServerCode, targetServer) // 执行当前逻辑
                        .eq(McpResourceCatalog::getEnabled, true) // 执行当前逻辑
        ); // 执行语句逻辑
        return rows.stream().map(this::toResourceDescriptor).toList(); // 返回处理结果
    } // 结束当前代码块

    @Override // 声明注解
    public McpResourceResult readResource(String serverCode, String resourceUri) { // 定义方法签名
        String targetServer = normalizeServerCode(serverCode); // 执行赋值操作
        McpResourceCatalog row = resourceCatalogMapper.selectOne( // 执行赋值操作
                new LambdaQueryWrapper<McpResourceCatalog>() // 执行当前逻辑
                        .eq(McpResourceCatalog::getServerCode, targetServer) // 执行当前逻辑
                        .eq(McpResourceCatalog::getResourceUri, resourceUri) // 执行当前逻辑
                        .eq(McpResourceCatalog::getEnabled, true) // 执行当前逻辑
                        .last("LIMIT 1") // 执行当前逻辑
        ); // 执行语句逻辑
        if (row == null) { // 进行条件判断
            return McpResourceResult.builder() // 返回处理结果
                    .status("error") // 执行当前逻辑
                    .serverCode(targetServer) // 执行当前逻辑
                    .resourceUri(resourceUri) // 执行当前逻辑
                    .data(Map.of("errorCode", "RESOURCE_NOT_FOUND", "message", "Resource not found")) // 执行当前逻辑
                    .build(); // 执行语句逻辑
        } // 结束当前代码块
        return McpResourceResult.builder() // 返回处理结果
                .status("success") // 执行当前逻辑
                .serverCode(targetServer) // 执行当前逻辑
                .resourceUri(resourceUri) // 执行当前逻辑
                .mimeType(row.getMimeType()) // 执行当前逻辑
                .data(row.getRawPayload() == null ? Collections.emptyMap() : row.getRawPayload()) // 执行赋值操作
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private String normalizeServerCode(String serverCode) { // 定义方法签名
        if (serverCode == null || serverCode.isBlank()) { // 进行条件判断
            return McpConstant.LOCAL_SERVER_CODE; // 返回处理结果
        } // 结束当前代码块
        return serverCode.trim(); // 返回处理结果
    } // 结束当前代码块

    private McpToolDescriptor toToolDescriptor(McpToolCatalog row) { // 定义方法签名
        return McpToolDescriptor.builder() // 返回处理结果
                .serverCode(row.getServerCode()) // 执行当前逻辑
                .toolName(row.getToolName()) // 执行当前逻辑
                .title(row.getTitle()) // 执行当前逻辑
                .description(row.getDescription()) // 执行当前逻辑
                .inputSchema(row.getInputSchema()) // 执行当前逻辑
                .outputSchema(row.getOutputSchema()) // 执行当前逻辑
                .requiresApproval(row.getRequiresApproval()) // 执行当前逻辑
                .sensitivity(row.getSensitivity()) // 执行当前逻辑
                .version(row.getVersion()) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private McpPromptDescriptor toPromptDescriptor(McpPromptCatalog row) { // 定义方法签名
        return McpPromptDescriptor.builder() // 返回处理结果
                .serverCode(row.getServerCode()) // 执行当前逻辑
                .promptName(row.getPromptName()) // 执行当前逻辑
                .title(row.getTitle()) // 执行当前逻辑
                .description(row.getDescription()) // 执行当前逻辑
                .argumentsSchema(row.getArgumentsSchema()) // 执行当前逻辑
                .version(row.getVersion()) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private McpResourceDescriptor toResourceDescriptor(McpResourceCatalog row) { // 定义方法签名
        return McpResourceDescriptor.builder() // 返回处理结果
                .serverCode(row.getServerCode()) // 执行当前逻辑
                .resourceUri(row.getResourceUri()) // 执行当前逻辑
                .name(row.getName()) // 执行当前逻辑
                .description(row.getDescription()) // 执行当前逻辑
                .mimeType(row.getMimeType()) // 执行当前逻辑
                .annotations(row.getAnnotations()) // 执行当前逻辑
                .build(); // 执行语句逻辑
    } // 结束当前代码块

    private Map<String, Object> parseMap(String json) { // 定义方法签名
        if (json == null || json.isBlank()) { // 进行条件判断
            return Collections.emptyMap(); // 返回处理结果
        } // 结束当前代码块
        try { // 尝试执行核心逻辑
            return objectMapper.readValue(json, new TypeReference<>() {}); // 返回处理结果
        } catch (Exception e) { // 开始新的代码块
            return Map.of("raw", json); // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块

    private String parseStatus(Map<String, Object> data) { // 定义方法签名
        Object status = data.get("status"); // 执行赋值操作
        if (status == null) { // 进行条件判断
            return "success"; // 返回处理结果
        } // 结束当前代码块
        String text = String.valueOf(status); // 执行赋值操作
        return text.isBlank() ? "success" : text; // 返回处理结果
    } // 结束当前代码块

    private String errorResult(String code, String message) { // 定义方法签名
        try { // 尝试执行核心逻辑
            return objectMapper.writeValueAsString(Map.of( // 返回处理结果
                    "status", "error", // 执行当前逻辑
                    "errorCode", code, // 执行当前逻辑
                    "message", message // 执行当前逻辑
            )); // 执行语句逻辑
        } catch (Exception e) { // 开始新的代码块
            log.warn("build error result failed", e); // 执行语句逻辑
            return "{\"status\":\"error\"}"; // 返回处理结果
        } // 结束当前代码块
    } // 结束当前代码块
} // 结束当前代码块
