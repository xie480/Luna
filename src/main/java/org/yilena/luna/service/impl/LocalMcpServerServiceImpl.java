package org.yilena.luna.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.yilena.luna.mapper.McpPromptCatalogMapper;
import org.yilena.luna.mapper.McpResourceCatalogMapper;
import org.yilena.luna.mapper.McpToolCatalogMapper;
import org.yilena.luna.mapper.McpToolImplMappingMapper;
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
public class LocalMcpServerServiceImpl implements LocalMcpServerService {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final JdbcTemplate jdbcTemplate;
    private final List<LocalMcpToolHandler> localToolHandlers;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private volatile Map<String, LocalMcpToolHandler> toolHandlerIndex;

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

        String implType = normalizeImplType(mapping.getImplType());
        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        String rawResult = switch (implType) {
            case "HTTP", "RPC", "WORKFLOW" -> invokeRoute(mapping, toolName, targetServer, args);
            case "SPRING_BEAN", "LOCAL_HANDLER" -> invokeSpringHandler(mapping, toolName, args);
            default -> errorResult("UNSUPPORTED_IMPL_TYPE", "Unsupported implType: " + implType);
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
        McpResourceResult dynamic = readDynamicResource(targetServer, resourceUri);
        if (dynamic != null) {
            return dynamic;
        }
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
        if (mapping.getRouteUri() == null || mapping.getRouteUri().isBlank()) {
            return errorResult("ROUTE_URI_REQUIRED", "routeUri is required for HTTP/RPC/WORKFLOW impl");
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
                return errorResult("ROUTE_CALL_FAILED", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "{}" : response.body();
        } catch (Exception e) {
            return errorResult("ROUTE_CALL_FAILED", e.getMessage());
        }
    }

    private String invokeSpringHandler(McpToolImplMapping mapping, String toolName, String args) {
        LocalMcpToolHandler.InvocationContext context = new LocalMcpToolHandler.InvocationContext(
                normalizeServerCode(mapping == null ? null : mapping.getServerCode()),
                toolName,
                normalizeImplType(mapping == null ? null : mapping.getImplType()),
                mapping == null ? null : mapping.getBeanName(),
                mapping == null ? null : mapping.getMethodName(),
                args == null || args.isBlank() ? "{}" : args
        );
        LocalMcpToolHandler handler = resolveToolHandler(mapping, toolName, context);
        if (handler == null) {
            return errorResult(
                    "LOCAL_TOOL_HANDLER_NOT_FOUND",
                    "No LocalMcpToolHandler registered for toolName=" + toolName
            );
        }
        try {
            return handler.handle(context);
        } catch (Exception e) {
            return errorResult("LOCAL_TOOL_HANDLER_FAILED", e.getMessage());
        }
    }

    private LocalMcpToolHandler resolveToolHandler(McpToolImplMapping mapping,
                                                   String toolName,
                                                   LocalMcpToolHandler.InvocationContext context) {
        Map<String, LocalMcpToolHandler> index = localToolHandlerIndex();
        String byTool = toolName == null ? "" : toolName.trim().toLowerCase(Locale.ROOT);
        if (!byTool.isBlank() && index.containsKey(byTool)) {
            LocalMcpToolHandler handler = index.get(byTool);
            if (handler != null && handler.supports(context)) {
                return handler;
            }
        }
        String byBean = mapping == null || mapping.getBeanName() == null
                ? ""
                : mapping.getBeanName().trim().toLowerCase(Locale.ROOT);
        if (!byBean.isBlank() && index.containsKey(byBean)) {
            LocalMcpToolHandler handler = index.get(byBean);
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

    private Map<String, Object> readKnowledge(String path, Map<String, String> query) {
        int limit = normalizeLimit(query.get("limit"), 10, 50);
        if ("/query".equalsIgnoreCase(path)) {
            String keyword = query.getOrDefault("q", query.getOrDefault("query", ""));
            String like = "%" + keyword + "%";
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    select id, title, content, source_type, source_path, created_at, updated_at
                    from knowledge_base
                    where (? = '' or title ilike ? or content ilike ?)
                    order by updated_at desc
                    limit ?
                    """,
                    keyword == null ? "" : keyword, like, like, limit
            );
            return Map.of("domain", "knowledge", "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/") && path.length() > 1) {
            String idText = path.substring(1);
            if (idText.chars().allMatch(Character::isDigit)) {
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                        "select id, title, content, source_type, source_path, created_at, updated_at from knowledge_base where id = ? limit 1",
                        Long.parseLong(idText)
                );
                return Map.of("domain", "knowledge", "count", rows.size(), "items", rows);
            }
        }
        return null;
    }

    private Map<String, Object> readUser(String path, Map<String, String> query) {
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        if ("/preferences/current".equalsIgnoreCase(path)) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select pref_key, pref_value, description, updated_at from user_preference where coalesce(deleted,0)=0 order by updated_at desc limit ?",
                    limit
            );
            return Map.of("domain", "user", "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/preferences/") && path.length() > "/preferences/".length()) {
            String key = decode(path.substring("/preferences/".length()));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select pref_key, pref_value, description, updated_at from user_preference where coalesce(deleted,0)=0 and pref_key = ? order by updated_at desc limit ?",
                    key, limit
            );
            return Map.of("domain", "user", "count", rows.size(), "items", rows);
        }
        return null;
    }

    private Map<String, Object> readMemory(String path, Map<String, String> query) {
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        String sessionId = query.getOrDefault("sessionId", "");
        if ("/session/current".equalsIgnoreCase(path)) {
            if (sessionId.isBlank()) {
                sessionId = AuthContextHolder.getSessionId();
            }
            if (sessionId == null || sessionId.isBlank()) {
                return Map.of("domain", "memory", "count", 0, "items", List.of(), "message", "sessionId unavailable");
            }
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select id, session_id, memory_type, content, weight, created_at, updated_at from luna_memory where session_id = ? order by updated_at desc limit ?",
                    sessionId, limit
            );
            return Map.of("domain", "memory", "sessionId", sessionId, "count", rows.size(), "items", rows);
        }
        if (path != null && path.startsWith("/session/") && path.length() > "/session/".length()) {
            String sid = decode(path.substring("/session/".length()));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select id, session_id, memory_type, content, weight, created_at, updated_at from luna_memory where session_id = ? order by updated_at desc limit ?",
                    sid, limit
            );
            return Map.of("domain", "memory", "sessionId", sid, "count", rows.size(), "items", rows);
        }
        return null;
    }

    private Map<String, Object> readSchedule(String path, Map<String, String> query) {
        int limit = normalizeLimit(query.get("limit"), 20, 100);
        if ("/today".equalsIgnoreCase(path)) {
            LocalDate today = LocalDate.now();
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    """
                    select id, content, trigger_time, status, task_type, created_at, updated_at
                    from schedule_task
                    where coalesce(deleted,0)=0
                      and trigger_time >= ?
                      and trigger_time < ?
                    order by trigger_time asc
                    limit ?
                    """,
                    today.atStartOfDay(), today.plusDays(1).atStartOfDay(), limit
            );
            return Map.of("domain", "schedule", "date", today.toString(), "count", rows.size(), "items", rows);
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
