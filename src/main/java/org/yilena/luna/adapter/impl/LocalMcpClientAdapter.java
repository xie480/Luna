package org.yilena.luna.adapter.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
import org.yilena.luna.entity.McpServerRegistry;
import org.yilena.luna.entity.McpToolImplMapping;
import org.yilena.luna.mapper.McpPromptCatalogMapper;
import org.yilena.luna.mapper.McpResourceCatalogMapper;
import org.yilena.luna.mapper.McpServerRegistryMapper;
import org.yilena.luna.mapper.McpToolCatalogMapper;
import org.yilena.luna.mapper.McpToolImplMappingMapper;
import org.yilena.luna.utils.AuthContextHolder;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
/**
 * 本地 MCP 适配器：
 * 从本地目录表读取工具/提示词/资源定义，并通过反射执行本地工具实现。
 */
public class LocalMcpClientAdapter implements McpClientAdapter {

    private final McpToolCatalogMapper toolCatalogMapper;
    private final McpPromptCatalogMapper promptCatalogMapper;
    private final McpResourceCatalogMapper resourceCatalogMapper;
    private final McpServerRegistryMapper serverRegistryMapper;
    private final McpToolImplMappingMapper toolImplMappingMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public List<McpToolDescriptor> listTools(String serverCode) {
        String targetServer = normalizeServerCode(serverCode);
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteListTools(registry, targetServer);
        }
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
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteCallTool(registry, targetServer, toolName, argumentsJson);
        }

        // 先从映射表解析 toolName 对应的本地实现入口。
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
        // 统一通过反射执行，返回值尽量解析为结构化 Map。
        String args = (argumentsJson == null || argumentsJson.isBlank()) ? "{}" : argumentsJson;
        String rawResult = switch (implType) {
            case "HTTP", "RPC", "WORKFLOW" -> invokeRoute(mapping, toolName, targetServer, args);
            case "SPRING_BEAN" -> errorResult("UNSUPPORTED_IMPL_TYPE", "SPRING_BEAN reflection path retired; use HTTP/RPC/WORKFLOW mapping");
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
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteListPrompts(registry, targetServer);
        }
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
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteGetPrompt(registry, targetServer, promptName, argumentsJson);
        }
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

        // prompt 返回内容由目录元数据 + 调用参数组成，便于上层统一消费。
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
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteListResources(registry, targetServer);
        }
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
        McpServerRegistry registry = loadServerRegistry(targetServer);
        if (!isLocalServer(registry)) {
            return remoteReadResource(registry, targetServer, resourceUri);
        }
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
        // 原样透传 rawPayload，避免丢失资源定义里的自定义字段。
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

    private McpServerRegistry loadServerRegistry(String serverCode) {
        try {
            return serverRegistryMapper.selectOne(
                    new LambdaQueryWrapper<McpServerRegistry>()
                            .eq(McpServerRegistry::getServerCode, serverCode)
                            .eq(McpServerRegistry::getEnabled, true)
                            .last("LIMIT 1")
            );
        } catch (Exception e) {
            log.warn("load mcp server registry failed, serverCode={}", serverCode, e);
            return null;
        }
    }

    private boolean isLocalServer(McpServerRegistry registry) {
        if (registry == null) {
            return true;
        }
        String transport = normalizeTransport(registry.getTransportType());
        if ("LOCAL".equals(transport)) {
            return true;
        }
        return registry.getBaseUrl() == null || registry.getBaseUrl().isBlank();
    }

    private String normalizeTransport(String transportType) {
        if (transportType == null || transportType.isBlank()) {
            return "HTTP";
        }
        return transportType.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeImplType(String implType) {
        if (implType == null || implType.isBlank()) {
            return "HTTP";
        }
        return implType.trim().toUpperCase(Locale.ROOT);
    }

    private List<McpToolDescriptor> remoteListTools(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/tools/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote tools/list parse failed, serverCode={}, body={}", serverCode, body, e);
            return Collections.emptyList();
        }
    }

    private McpToolCallResult remoteCallTool(McpServerRegistry registry, String serverCode, String toolName, String argumentsJson) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "toolName", toolName == null ? "" : toolName,
                "argumentsJson", argumentsJson == null ? "{}" : argumentsJson
        ));
        String body = remotePost(registry, "/tools/call", payload, 15000);
        try {
            return objectMapper.readValue(body, McpToolCallResult.class);
        } catch (Exception e) {
            return McpToolCallResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .toolName(toolName)
                    .rawResult(errorResult("REMOTE_CALL_PARSE_FAILED", e.getMessage()))
                    .data(Map.of("errorCode", "REMOTE_CALL_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private List<McpPromptDescriptor> remoteListPrompts(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/prompts/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote prompts/list parse failed, serverCode={}", serverCode, e);
            return Collections.emptyList();
        }
    }

    private McpPromptResult remoteGetPrompt(McpServerRegistry registry, String serverCode, String promptName, String argumentsJson) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "promptName", promptName == null ? "" : promptName,
                "argumentsJson", argumentsJson == null ? "{}" : argumentsJson
        ));
        String body = remotePost(registry, "/prompts/get", payload, 10000);
        try {
            return objectMapper.readValue(body, McpPromptResult.class);
        } catch (Exception e) {
            return McpPromptResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .promptName(promptName)
                    .promptContent(Map.of("errorCode", "REMOTE_PROMPT_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
    }

    private List<McpResourceDescriptor> remoteListResources(McpServerRegistry registry, String serverCode) {
        String body = remoteGet(registry, "/resources/list?serverCode=" + encode(serverCode), 10000);
        try {
            return objectMapper.readValue(body, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("remote resources/list parse failed, serverCode={}", serverCode, e);
            return Collections.emptyList();
        }
    }

    private McpResourceResult remoteReadResource(McpServerRegistry registry, String serverCode, String resourceUri) {
        String payload = toJson(Map.of(
                "serverCode", serverCode,
                "resourceUri", resourceUri == null ? "" : resourceUri
        ));
        String body = remotePost(registry, "/resources/read", payload, 10000);
        try {
            return objectMapper.readValue(body, McpResourceResult.class);
        } catch (Exception e) {
            return McpResourceResult.builder()
                    .status("error")
                    .serverCode(serverCode)
                    .resourceUri(resourceUri)
                    .data(Map.of("errorCode", "REMOTE_RESOURCE_PARSE_FAILED", "message", e.getMessage()))
                    .build();
        }
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

    private String remoteGet(McpServerRegistry registry, String path, int timeoutMs) {
        String transport = normalizeTransport(registry.getTransportType());
        if (!"HTTP".equals(transport) && !"SSE".equals(transport) && !"WS".equals(transport) && !"STDIO".equals(transport)) {
            return errorResult("UNSUPPORTED_TRANSPORT", "transport_type " + transport + " is not supported");
        }
        if (!"HTTP".equals(transport) && !"SSE".equals(transport)) {
            log.warn("transport_type={} not fully supported yet; fallback to HTTP GET", transport);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(joinUrl(registry.getBaseUrl(), path)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .GET();
            applyAuth(builder, registry);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("REMOTE_HTTP_ERROR", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "[]" : response.body();
        } catch (Exception e) {
            return errorResult("REMOTE_HTTP_ERROR", e.getMessage());
        }
    }

    private String remotePost(McpServerRegistry registry, String path, String payload, int timeoutMs) {
        String transport = normalizeTransport(registry.getTransportType());
        if (!"HTTP".equals(transport) && !"SSE".equals(transport) && !"WS".equals(transport) && !"STDIO".equals(transport)) {
            return errorResult("UNSUPPORTED_TRANSPORT", "transport_type " + transport + " is not supported");
        }
        if (!"HTTP".equals(transport) && !"SSE".equals(transport)) {
            log.warn("transport_type={} not fully supported yet; fallback to HTTP POST", transport);
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(joinUrl(registry.getBaseUrl(), path)))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload == null ? "{}" : payload));
            applyAuth(builder, registry);
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return errorResult("REMOTE_HTTP_ERROR", "status=" + response.statusCode() + ", body=" + response.body());
            }
            return response.body() == null ? "{}" : response.body();
        } catch (Exception e) {
            return errorResult("REMOTE_HTTP_ERROR", e.getMessage());
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

    private void applyAuth(HttpRequest.Builder builder, McpServerRegistry registry) {
        if (registry == null || registry.getAuthType() == null || registry.getAuthType().isBlank()) {
            return;
        }
        String authType = registry.getAuthType().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> config = registry.getAuthConfig() == null ? Map.of() : registry.getAuthConfig();
        if ("BEARER".equals(authType)) {
            String token = String.valueOf(config.getOrDefault("token", ""));
            if (!token.isBlank()) {
                builder.header("Authorization", "Bearer " + token.trim());
            }
            return;
        }
        if ("BASIC".equals(authType)) {
            String user = String.valueOf(config.getOrDefault("username", ""));
            String pass = String.valueOf(config.getOrDefault("password", ""));
            if (!user.isBlank()) {
                String raw = user + ":" + pass;
                String encoded = Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + encoded);
            }
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String p = path == null ? "" : path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }

    private String encode(String text) {
        return URLEncoder.encode(text == null ? "" : text, StandardCharsets.UTF_8);
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
