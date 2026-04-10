package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
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
import org.yilena.luna.mapper.KnowledgeBaseMapper;
import org.yilena.luna.mapper.MemoryMapper;
import org.yilena.luna.mapper.McpPromptCatalogMapper;
import org.yilena.luna.mapper.McpResourceCatalogMapper;
import org.yilena.luna.mapper.McpToolCatalogMapper;
import org.yilena.luna.mapper.McpToolImplMappingMapper;
import org.yilena.luna.mapper.ScheduleTaskMapper;
import org.yilena.luna.mapper.UserPreferenceMapper;
import org.yilena.luna.service.LocalMcpServerService;
import org.yilena.luna.service.local.LocalMcpToolHandler;
import org.yilena.luna.utils.AuthContextHolder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 本地 MCP 服务实现，负责把本地目录中的工具、Prompt、资源包装成 MCP 语义接口并执行对应实现。
 */
public class LocalMcpServerServiceImpl implements LocalMcpServerService {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final UserPreferenceMapper userPreferenceMapper;
    private final MemoryMapper memoryMapper;
    private final ScheduleTaskMapper scheduleTaskMapper;
    private final List<LocalMcpToolHandler> localToolHandlers;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile Map<String, LocalMcpToolHandler> toolHandlerIndex;

    @Value("${luna.mcp.execution.allow-spring-bean:false}")
    private boolean allowSpringBean;

    @Value("${luna.mcp.execution.allow-legacy-mode:false}")
    private boolean allowLegacyMode;

    @Override
    public List<McpToolDescriptor> listTools(String serverCode) {
        /**
         * 先按服务编码加载所有启用中的工具目录记录，再转换为 MCP 对外描述对象。
         */
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
        /**
         * 先根据服务编码和工具名查找执行映射，映射不存在时直接返回标准化错误结果。
         */
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

        String implType = normalizeImplType(mapping.getImplType());
        String executionMode = normalizeExecutionMode(mapping.getExecutionMode());

        /**
         * 在真正执行前先校验兼容模式开关，避免被禁用的旧执行模式或 Bean 模式误入运行时。
         */
        if ("LEGACY".equals(executionMode) && !allowLegacyMode) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .toolName(toolName)
                    .rawResult(errorResult("TOOL_LEGACY_MODE_DISABLED", "LEGACY execution mode is disabled"))
                    .data(Map.of("errorCode", "TOOL_LEGACY_MODE_DISABLED", "message", "LEGACY execution mode is disabled"))
                    .build();
        }
        if ("SPRING_BEAN".equals(implType) && !"LEGACY".equals(executionMode)) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(targetServer)
                    .toolName(toolName)
                    .rawResult(errorResult("TOOL_IMPL_MODE_INVALID", "SPRING_BEAN impl requires LEGACY execution mode"))
                    .data(Map.of("errorCode", "TOOL_IMPL_MODE_INVALID", "message", "SPRING_BEAN impl requires LEGACY execution mode"))
                    .build();
        }

        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        /**
         * 根据实现类型分发到路由调用、本地处理器或兼容 Bean 模式，并统一收敛返回结果结构。
         */
        String rawResult = switch (implType) {
            case "HTTP", "RPC", "WORKFLOW" -> invokeRoute(mapping, toolName, targetServer, args);
            case "LOCAL_HANDLER" -> invokeLocalHandler(mapping, toolName, args);
            case "SPRING_BEAN" -> invokeSpringBeanCompat(mapping, toolName, args);
            default -> errorResult("TOOL_UNSUPPORTED_IMPL_TYPE", "Unsupported implType: " + implType);
        };
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
        /**
         * 读取启用中的 Prompt 目录并转换为 MCP Prompt 描述，供外部发现和调用。
         */
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
        /**
         * 先查询目标 Prompt 是否存在，缺失时返回明确错误，避免下游拿到空内容误判成功。
         */
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

        /**
         * 对于目录中存在的 Prompt，直接回传元信息、原始载荷和本次调用参数，交由调用方继续组装。
         */
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
        /**
         * 合并静态目录资源与动态资源模板，既保留数据库配置，也暴露运行时查询入口。
         */
        String targetServer = normalizeServerCode(serverCode);
        List<McpResourceCatalog> rows = resourceCatalogMapper.selectList(
                new LambdaQueryWrapper<McpResourceCatalog>()
                        .eq(McpResourceCatalog::getServerCode, targetServer)
                        .eq(McpResourceCatalog::getEnabled, true)
        );
        Map<String, McpResourceDescriptor> merged = new LinkedHashMap<>();
        rows.stream()
                .map(this::toResourceDescriptor)
                .forEach(item -> merged.putIfAbsent(item.getResourceUri(), item));
        dynamicResourceDescriptors(targetServer)
                .forEach(item -> merged.putIfAbsent(item.getResourceUri(), item));
        return new ArrayList<>(merged.values());
    }

    @Override
    public McpResourceResult readResource(String serverCode, String resourceUri) {
        /**
         * 优先尝试读取动态资源模板，命中后可以直接基于 URI 参数生成实时数据。
         */
        String targetServer = normalizeServerCode(serverCode);
        McpResourceResult dynamic = readDynamicResource(targetServer, resourceUri);
        if (dynamic != null) {
            return dynamic;
        }
        /**
         * 动态资源未命中时再回退到静态目录资源，保证两类资源可以共存。
         */
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

    private String invokeRoute(McpToolImplMapping mapping, String toolName, String serverCode, String args) {
        /**
         * 对 HTTP、RPC、工作流路由类实现统一走远端调用，保持 MCP 本地服务只做网关转发。
         */
        if (mapping.getRouteUri() == null || mapping.getRouteUri().isBlank()) {
            return errorResult("TOOL_ROUTE_URI_REQUIRED", "routeUri is required for HTTP/RPC/WORKFLOW impl");
        }
        int timeout = mapping.getTimeoutMs() == null || mapping.getTimeoutMs() <= 0 ? 10000 : mapping.getTimeoutMs();
        try {
            String payload = toJson(Map.of(
                    "serverCode", serverCode,
                    "toolName", toolName == null ? "" : toolName,
                    "argumentsJson", args == null ? "{}" : args
            ));
            HttpRequest request = HttpRequest.newBuilder(URI.create(mapping.getRouteUri().trim()))
                    .timeout(Duration.ofMillis(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("TOOL_ROUTE_CALL_FAILED", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "{}" : response.body();
        } catch (Exception e) {
            return errorResult("TOOL_ROUTE_CALL_FAILED", e.getMessage());
        }
    }

    private String invokeLocalHandler(McpToolImplMapping mapping, String toolName, String args) {
        /**
         * 本地处理器模式下先构造调用上下文，再按工具名和 supports 规则定位最合适的处理器。
         */
        LocalMcpToolHandler.InvocationContext context = new LocalMcpToolHandler.InvocationContext(
                normalizeServerCode(mapping == null ? null : mapping.getServerCode()),
                toolName,
                normalizeImplType(mapping == null ? null : mapping.getImplType()),
                mapping == null ? "" : mapping.getBeanName(),
                mapping == null ? "" : mapping.getMethodName(),
                args == null || args.isBlank() ? "{}" : args
        );
        LocalMcpToolHandler handler = resolveLocalHandler(toolName, context);
        if (handler != null) {
            try {
                return handler.handle(context);
            } catch (Exception e) {
                return errorResult("TOOL_LOCAL_HANDLER_FAILED", e.getMessage());
            }
        }
        return errorResult("TOOL_LOCAL_HANDLER_NOT_FOUND", "No LocalMcpToolHandler registered for toolName=" + toolName);
    }

    private String invokeSpringBeanCompat(McpToolImplMapping mapping, String toolName, String args) {
        /**
         * 兼容旧版 Spring Bean 执行模式时，仍复用本地处理器抽象，避免直接反射调用扩散。
         */
        if (!allowSpringBean) {
            return errorResult(
                    "TOOL_IMPL_DISABLED",
                    "SPRING_BEAN execution is disabled by config luna.mcp.execution.allow-spring-bean=false"
            );
        }
        LocalMcpToolHandler.InvocationContext context = new LocalMcpToolHandler.InvocationContext(
                normalizeServerCode(mapping == null ? null : mapping.getServerCode()),
                toolName,
                "SPRING_BEAN",
                mapping == null ? "" : mapping.getBeanName(),
                mapping == null ? "" : mapping.getMethodName(),
                args == null || args.isBlank() ? "{}" : args
        );
        LocalMcpToolHandler handler = resolveLocalHandler(toolName, context);
        if (handler == null) {
            return errorResult("TOOL_COMPAT_HANDLER_NOT_FOUND", "No compatible SPRING_BEAN handler found");
        }
        try {
            return handler.handle(context);
        } catch (Exception e) {
            return errorResult("TOOL_COMPAT_EXECUTION_FAILED", e.getMessage());
        }
    }

    private LocalMcpToolHandler resolveLocalHandler(String toolName,
                                                    LocalMcpToolHandler.InvocationContext context) {
        /**
         * 先按工具名索引快速定位处理器，找不到时再遍历 supports 兜底，兼顾性能和灵活匹配。
         */
        Map<String, LocalMcpToolHandler> index = localToolHandlerIndex();
        String byTool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        if (!byTool.isBlank() && index.containsKey(byTool)) {
            LocalMcpToolHandler handler = index.get(byTool);
            if (handler != null && handler.supports(context)) {
                return handler;
            }
        }
        if (localToolHandlers != null) {
            for (LocalMcpToolHandler handler : localToolHandlers) {
                if (handler != null && handler.supports(context)) {
                    return handler;
                }
            }
        }
        return null;
    }

    private Map<String, LocalMcpToolHandler> localToolHandlerIndex() {
        Map<String, LocalMcpToolHandler> cached = toolHandlerIndex;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (toolHandlerIndex != null) {
                return toolHandlerIndex;
            }
            /**
             * 初始化工具处理器索引时同时登记主名称和别名，降低不同路由名带来的匹配成本。
             */
            Map<String, LocalMcpToolHandler> out = new HashMap<>();
            List<LocalMcpToolHandler> handlers = localToolHandlers == null ? List.of() : new ArrayList<>(localToolHandlers);
            for (LocalMcpToolHandler handler : handlers) {
                if (handler == null || handler.toolName() == null || handler.toolName().isBlank()) {
                    continue;
                }
                String key = handler.toolName().trim().toLowerCase(Locale.ROOT);
                out.putIfAbsent(key, handler);
                List<String> aliases = handler.aliases();
                if (aliases == null || aliases.isEmpty()) {
                    continue;
                }
                for (String alias : aliases) {
                    if (alias == null || alias.isBlank()) {
                        continue;
                    }
                    out.putIfAbsent(alias.trim().toLowerCase(Locale.ROOT), handler);
                }
            }
            toolHandlerIndex = out;
            return toolHandlerIndex;
        }
    }

    private McpResourceResult readDynamicResource(String serverCode, String resourceUri) {
        /**
         * 动态资源以 resource:// 协议约定不同业务域，读取时根据 host 路由到具体查询实现。
         */
        if (resourceUri == null || resourceUri.isBlank() || !resourceUri.startsWith("resource://")) {
            return null;
        }
        try {
            URI uri = URI.create(resourceUri.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath();
            Map<String, String> query = parseQuery(uri.getRawQuery());
            Map<String, Object> data = switch (host) {
                case "knowledge" -> readKnowledge(path, query);
                case "user" -> readUser(path, query);
                case "memory" -> readMemory(path, query);
                case "schedule" -> readSchedule(path, query);
                default -> null;
            };
            if (data == null) {
                return null;
            }
            return McpResourceResult.builder()
                    .status("success")
                    .serverCode(serverCode)
                    .resourceUri(resourceUri)
                    .mimeType("application/json")
                    .data(data)
                    .build();
        } catch (Exception e) {
            return McpResourceResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .resourceUri(resourceUri)
                    .data(Map.of("errorCode", "RESOURCE_READ_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private List<McpResourceDescriptor> dynamicResourceDescriptors(String serverCode) {
        /**
         * 动态资源模板用于声明可发现的运行时资源入口，避免客户端不知道 URI 规范。
         */
        return List.of(
                dynamicResourceDescriptor(serverCode, "resource://knowledge/query", "knowledge.query", "Knowledge query resource template"),
                dynamicResourceDescriptor(serverCode, "resource://user/preferences/current", "user.preferences.current", "User preference resource template"),
                dynamicResourceDescriptor(serverCode, "resource://memory/session/current", "memory.session.current", "Session memory resource template"),
                dynamicResourceDescriptor(serverCode, "resource://schedule/today", "schedule.today", "Today schedule resource template"),
                dynamicResourceDescriptor(serverCode, "resource://schedule/task/{id}", "schedule.task.by-id", "Schedule task by id resource template")
        );
    }

    private McpResourceDescriptor dynamicResourceDescriptor(String serverCode, String uri, String name, String description) {
        return McpResourceDescriptor.builder()
                .serverCode(serverCode)
                .resourceUri(uri)
                .name(name)
                .description(description)
                .mimeType("application/json")
                .annotations(Map.of("discoverability", "dynamic-template"))
                .build();
    }

    private Map<String, Object> readKnowledge(String path, Map<String, String> query) {
        /**
         * 知识资源支持按关键词查询和按 ID 读取两种方式，分别服务搜索和精确定位场景。
         */
        int limit = normalizeLimit(query.get("limit"), 10, 50);
        if ("/query".equalsIgnoreCase(path)) {
            String keyword = query.getOrDefault("q", query.getOrDefault("query", ""));
            List<Map<String, Object>> rows = knowledgeBaseMapper.selectResourceKnowledgeByKeyword(keyword == null ? "" : keyword, limit);
            return Map.of("domain", "knowledge", "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/") && path.length() > 1) {
            String idText = path.substring(1);
            if (idText.chars().allMatch(Character::isDigit)) {
                List<Map<String, Object>> rows = knowledgeBaseMapper.selectResourceKnowledgeById(Long.parseLong(idText));
                return Map.of("domain", "knowledge", "count", rows.size(), "items", rows);
            }
        }
        return null;
    }

    private Map<String, Object> readUser(String path, Map<String, String> query) {
        /**
         * 用户资源主要用于暴露当前偏好画像，可按全部偏好或指定偏好键读取。
         */
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        if ("/preferences/current".equalsIgnoreCase(path)) {
            List<Map<String, Object>> rows = userPreferenceMapper.selectResourcePreferences(limit);
            return Map.of("domain", "user", "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/preferences/") && path.length() > "/preferences/".length()) {
            String key = decode(path.substring("/preferences/".length()));
            List<Map<String, Object>> rows = userPreferenceMapper.selectResourcePreferencesByKey(key, limit);
            return Map.of("domain", "user", "count", rows.size(), "items", rows);
        }
        return null;
    }

    private Map<String, Object> readMemory(String path, Map<String, String> query) {
        /**
         * 记忆资源按会话维度读取，优先使用显式 sessionId，缺失时回退到当前登录会话。
         */
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        String sessionId = query.getOrDefault("sessionId", "");
        if ("/session/current".equalsIgnoreCase(path)) {
            if (sessionId.isBlank()) {
                sessionId = AuthContextHolder.getSessionId();
            }
            if (sessionId == null || sessionId.isBlank()) {
                return Map.of("domain", "memory", "count", 0, "items", List.of(), "message", "sessionId unavailable");
            }
            List<Map<String, Object>> rows = memoryMapper.selectResourceMemoryBySessionId(sessionId, limit);
            return Map.of("domain", "memory", "sessionId", sessionId, "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/session/") && path.length() > "/session/".length()) {
            String sid = decode(path.substring("/session/".length()));
            List<Map<String, Object>> rows = memoryMapper.selectResourceMemoryBySessionId(sid, limit);
            return Map.of("domain", "memory", "sessionId", sid, "count", rows.size(), "items", rows);
        }
        return null;
    }

    private Map<String, Object> readSchedule(String path, Map<String, String> query) {
        /**
         * 日程资源同时支持当天任务概览和按任务 ID 精确查询，方便前端做看板和详情展示。
         */
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        if ("/today".equalsIgnoreCase(path)) {
            LocalDate today = LocalDate.now();
            List<Map<String, Object>> rows = scheduleTaskMapper.selectResourceScheduleByTriggerBetween(
                    today.atStartOfDay(),
                    today.plusDays(1).atStartOfDay(),
                    limit
            );
            return Map.of("domain", "schedule", "date", today.toString(), "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/task/") && path.length() > "/task/".length()) {
            String idText = decode(path.substring("/task/".length()));
            if (!idText.chars().allMatch(Character::isDigit)) {
                return Map.of("domain", "schedule", "count", 0, "items", List.of(), "message", "invalid task id");
            }
            List<Map<String, Object>> rows = scheduleTaskMapper.selectResourceScheduleById(Long.parseLong(idText));
            return Map.of("domain", "schedule", "count", rows.size(), "items", rows);
        }
        return null;
    }

    private String normalizeServerCode(String serverCode) {
        if (serverCode == null || serverCode.isBlank()) {
            return McpConstant.LOCAL_SERVER_CODE;
        }
        return serverCode.trim();
    }

    private String normalizeImplType(String implType) {
        if (implType == null || implType.isBlank()) {
            return "LOCAL_HANDLER";
        }
        return implType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeExecutionMode(String executionMode) {
        if (executionMode == null || executionMode.isBlank()) {
            return "MCP";
        }
        String normalized = executionMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MCP", "LEGACY" -> normalized;
            default -> "MCP";
        };
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

    private Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair == null || pair.isBlank()) {
                continue;
            }
            int idx = pair.indexOf('=');
            if (idx < 0) {
                out.put(decode(pair), "");
            } else {
                out.put(decode(pair.substring(0, idx)), decode(pair.substring(idx + 1)));
            }
        }
        return out;
    }

    private String decode(String text) {
        if (text == null) {
            return "";
        }
        return java.net.URLDecoder.decode(text, StandardCharsets.UTF_8);
    }

    private int normalizeLimit(String raw, int defaultLimit, int maxLimit) {
        try {
            int v = Integer.parseInt(raw == null ? "" : raw.trim());
            if (v <= 0) {
                return defaultLimit;
            }
            return Math.min(v, maxLimit);
        } catch (Exception ignore) {
            return defaultLimit;
        }
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
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
